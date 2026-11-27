"""Bounded HTTPS transport for the fixed public catalogue sources.

Every redirect is checked. Direct connections use only the public IPs resolved
for that connection, while TLS still verifies the original hostname. We fail
closed with an inherited proxy rather than bypassing its policy or assuming
we can validate the proxy's destination DNS.
"""
import http.client
import ipaddress
import socket
import ssl
import urllib.parse
import urllib.request

ALLOWED_HOSTS = frozenset({
    "sound-books.net", "www.sound-books.net", "audiobook.co.ua", "www.audiobook.co.ua",
    "chytaylo.com.ua", "www.chytaylo.com.ua", "knigi-online.com.ua", "www.knigi-online.com.ua",
    "chitaka.com.ua", "www.chitaka.com.ua", "sluhay.com.ua", "www.sluhay.com.ua",
    "librivox.org", "www.librivox.org",
})
MAX_RESPONSE_BYTES = 8 * 1024 * 1024


def validate_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS
            or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 443) or parsed.fragment):
        raise ValueError("catalog URL must use HTTPS on an approved public source")
    return parsed.hostname


def public_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None):
    host, port = address
    if host not in ALLOWED_HOSTS or port != 443:
        raise ValueError("connection destination is not an approved source")
    addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(row[4][0]).is_global for row in addresses):
        raise ValueError("catalog host resolves to a non-public address")
    last_error = None
    for family, kind, protocol, _, target in addresses:
        connection = socket.socket(family, kind, protocol)
        try:
            if timeout is not socket._GLOBAL_DEFAULT_TIMEOUT:
                connection.settimeout(timeout)
            if source_address:
                connection.bind(source_address)
            connection.connect(target)  # Already resolved: no second DNS lookup/rebinding window.
            return connection
        except OSError as error:
            connection.close()
            last_error = error
    raise last_error or OSError("no public address available")


class PublicHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._create_connection = public_connection


class PublicHTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, request):
        validate_url(request.full_url)
        return self.do_open(PublicHTTPSConnection, request, context=ssl.create_default_context())


class PublicRedirectHandler(urllib.request.HTTPRedirectHandler):
    max_redirections = 5

    def redirect_request(self, request, response, code, message, headers, new_url):
        validate_url(new_url)
        return super().redirect_request(request, response, code, message, headers, new_url)


def fetch_text(url, timeout=25, headers=None):
    host = validate_url(url)
    proxies = urllib.request.getproxies()
    if (proxies.get("https") or proxies.get("all")) and not urllib.request.proxy_bypass(host):
        raise ValueError("configured proxy needs an explicitly validated catalogue transport; it is not bypassed")
    request_headers = {"User-Agent": "slukhayka-catalog/1", "Accept-Encoding": "identity"}
    request_headers.update(headers or {})
    request = urllib.request.Request(url, headers=request_headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                        PublicHTTPSHandler(), PublicRedirectHandler())
    with opener.open(request, timeout=timeout) as response:
        validate_url(response.geturl())
        length = response.headers.get("Content-Length")
        if length is not None and int(length) > MAX_RESPONSE_BYTES:
            raise ValueError("catalog response exceeds 8 MiB")
        content = response.read(MAX_RESPONSE_BYTES + 1)
        if len(content) > MAX_RESPONSE_BYTES:
            raise ValueError("catalog response exceeds 8 MiB")
        return content.decode("utf-8", "replace")
