"""No live networking: untrusted catalogue URLs must never reach local resources."""
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
import socket
import urllib.request

sys.path.insert(0, str(Path(__file__).parents[1] / "recommend"))
import public_catalog_http as transport

URL = "https://chitaka.com.ua/knigi/example/"


class PublicCatalogHttpTest(unittest.TestCase):
    def test_rejects_schemes_credentials_private_hosts_and_ports_before_io(self):
        for url in ("file:///etc/hosts", "http://chitaka.com.ua/a", "https://127.0.0.1/a",
                    "https://[::1]/a", "https://chitaka.com.ua.evil.test/a",
                    "https://user:password@chitaka.com.ua/a", "https://chitaka.com.ua:8443/a",
                    "https://chitaka.com.ua:invalid/a"):
            with self.subTest(url=url), patch.object(transport.urllib.request, "getproxies", return_value={}), \
                    patch.object(transport.urllib.request, "build_opener") as opener:
                with self.assertRaises(transport.CatalogSecurityError):
                    transport.fetch_text(url)
                opener.assert_not_called()

    def test_redirects_cannot_escape_allowlist_or_https(self):
        handler = transport.PublicRedirectHandler()
        request = urllib.request.Request(URL)
        for destination in ("file:///tmp/data", "http://chitaka.com.ua/a", "https://localhost/a"):
            with self.assertRaises(transport.CatalogSecurityError):
                handler.redirect_request(request, None, 302, "Found", {}, destination)
        redirected = handler.redirect_request(request, None, 302, "Found", {},
                                              "https://www.chitaka.com.ua/knigi/next/")
        self.assertEqual("www.chitaka.com.ua", redirected.host)

    def test_private_dns_answers_are_rejected_before_socket_creation(self):
        for address in ("127.0.0.1", "192.168.1.1", "169.254.169.254", "::1", "fd00::1"):
            with self.subTest(address=address), patch.object(transport.socket, "getaddrinfo",
                    return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 443))]), \
                    patch.object(transport.socket, "socket") as connect:
                with self.assertRaises(transport.CatalogSecurityError):
                    transport.public_connection(("chitaka.com.ua", 443))
                connect.assert_not_called()

    def test_connection_uses_resolved_ip_without_a_second_dns_lookup(self):
        target = ("1.1.1.1", 443)
        with patch.object(transport.socket, "getaddrinfo", return_value=[
                (socket.AF_INET, socket.SOCK_STREAM, 6, "", target)]) as dns, \
                patch.object(transport.socket, "socket") as factory:
            connection = transport.public_connection(("chitaka.com.ua", 443), 20)
            self.assertEqual(factory.return_value, connection)
            connection.connect.assert_called_once_with(target)
            self.assertEqual(1, dns.call_count)

    def test_proxy_is_not_silently_bypassed(self):
        with patch.object(transport.urllib.request, "getproxies", return_value={"https": "http://proxy"}), \
                patch.object(transport.urllib.request, "proxy_bypass", return_value=False), \
                patch.object(transport.urllib.request, "build_opener") as opener:
            with self.assertRaisesRegex(ValueError, "not bypassed"):
                transport.fetch_text(URL)
            opener.assert_not_called()

    def test_response_is_bounded_even_without_content_length(self):
        response = MagicMock()
        response.geturl.return_value = URL
        response.headers = {}
        response.read.return_value = b"x" * 17
        response.__enter__.return_value = response
        with patch.object(transport, "MAX_RESPONSE_BYTES", 16), \
                patch.object(transport.urllib.request, "getproxies", return_value={}), \
                patch.object(transport.urllib.request, "build_opener") as opener:
            opener.return_value.open.return_value = response
            with self.assertRaisesRegex(ValueError, "exceeds"):
                transport.fetch_text(URL)
            response.read.assert_called_once_with(17)

    def test_oversized_content_length_is_rejected_before_read(self):
        response = MagicMock()
        response.geturl.return_value = URL
        response.headers = {"Content-Length": str(transport.MAX_RESPONSE_BYTES + 1)}
        response.__enter__.return_value = response
        with patch.object(transport.urllib.request, "getproxies", return_value={}), \
                patch.object(transport.urllib.request, "build_opener") as opener:
            opener.return_value.open.return_value = response
            with self.assertRaisesRegex(ValueError, "exceeds"):
                transport.fetch_text(URL)
            response.read.assert_not_called()
