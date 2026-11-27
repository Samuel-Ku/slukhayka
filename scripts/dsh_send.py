#!/usr/bin/env python3
"""Send ASCII text to an explicitly selected DSH session and verify delivery.

Does not navigate sessions, unlock devices, change settings or retry a send.
The caller checks execution separately through GitHub or an explicit acknowledgement.
"""
import argparse
import json
import re
import shlex
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree


class DeliveryError(Exception):
    pass


def inspect_session(nodes, title):
    titles = [n.get('text') for n in nodes if n.get('resource-id') == 'session-title']
    if titles != [title]:
        raise DeliveryError('session title mismatch; select the intended session first')
    composers = [n for n in nodes if n.get('resource-id') == 'composer-input']
    if len(composers) != 1:
        raise DeliveryError('composer unavailable')
    return composers[0]


def send(ui, title, message):
    if not message or not message.isascii() or any(ord(c) < 32 or ord(c) > 126 for c in message):
        raise DeliveryError('this transport accepts printable ASCII only')
    composer = inspect_session(ui.nodes(), title)
    if composer.get('text'):
        raise DeliveryError('composer contains a draft; preserved')
    ui.tap(composer)
    ui.type_text(message)
    nodes = ui.nodes()
    if inspect_session(nodes, title).get('text') != message:
        raise DeliveryError('input changed; draft preserved, nothing sent')
    buttons = [n for n in nodes if n.get('content-desc') == 'Send' and n.get('enabled') == 'true']
    if len(buttons) != 1:
        raise DeliveryError('session is busy or Send is ambiguous; draft preserved')
    ui.tap(buttons[0])
    for _ in range(5):
        nodes = ui.nodes()
        composer = inspect_session(nodes, title)
        posted = any(n.get('text') == message and n.get('resource-id') != 'composer-input'
                     for n in nodes)
        if posted and not composer.get('text'):
            return {'delivery': 'visible-in-history', 'execution': 'unconfirmed', 'sessionTitle': title}
        time.sleep(.4)
    raise DeliveryError('delivery unconfirmed; inspect history before any retry')


class AdbUi:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args):
        return subprocess.check_output(['adb', '-s', self.serial, *args], timeout=20)

    def nodes(self):
        raw = self.adb('exec-out', 'uiautomator', 'dump', '/dev/tty').decode()
        end = raw.find('</hierarchy>')
        if end < 0:
            raise DeliveryError('UI snapshot unavailable')
        return [n for n in ElementTree.fromstring(raw[:end + len('</hierarchy>')]).iter('node')
                if n.get('package') == 'com.dshremote.app' and n.get('bounds') != '[0,0][0,0]']

    def tap(self, node):
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if not bounds:
            raise DeliveryError('element bounds unavailable')
        x, y, right, bottom = map(int, bounds.groups())
        if right <= x or bottom <= y:
            raise DeliveryError('element is not visible')
        self.adb('shell', 'input', 'tap', str((x + right) // 2), str((y + bottom) // 2))

    def type_text(self, message):
        self.adb('shell', 'input', 'text', shlex.quote(message.replace(' ', '%s')))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--session-title', required=True, help='exact UI title; use a unique session title')
    p.add_argument('--message-file', required=True)
    args = p.parse_args()
    try:
        result = send(AdbUi(args.serial), args.session_title,
                      Path(args.message_file).read_text(encoding='utf-8').removesuffix('\n'))
    except (DeliveryError, OSError, subprocess.SubprocessError, ElementTree.ParseError) as error:
        print(json.dumps({'error': str(error)}))
        return 2
    print(json.dumps(result))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
