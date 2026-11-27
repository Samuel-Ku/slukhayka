"""Advisory next steps for a PR queue; the repository merge gate is authoritative."""


def queue_snapshot(pulls, state, limit=3):
    if not isinstance(state, dict) or not isinstance(state.get('pullRequests', {}), dict):
        raise ValueError('queue state must contain a pullRequests object')
    records = state.get('pullRequests', {})
    active = 0
    rows = []
    for pull in sorted(pulls, key=lambda p: p['number']):
        number = pull['number']
        head = pull.get('headRefOid')
        record = records.get(str(number), {})
        if not isinstance(record, dict):
            raise ValueError(f'invalid queue record for #{number}')
        fresh = bool(head) and record.get('head') == head
        reviews = record.get('reviews', {}) if fresh else {}
        if not isinstance(reviews, dict):
            raise ValueError(f'invalid reviews for #{number}')
        verdicts = [reviews.get(axis, {}) for axis in ('Spec', 'Standards')]
        if any(not isinstance(v, dict) for v in verdicts):
            raise ValueError(f'invalid review receipt for #{number}')
        current = [v if v.get('head') == head else {} for v in verdicts]
        reviewing = any(v.get('status') == 'in-progress' for v in current)
        active += int(reviewing)
        checks = {}
        # Historical failed attempts must not hide a later successful attempt.
        for check in sorted(pull.get('statusCheckRollup') or [],
                            key=lambda c: c.get('completedAt') or c.get('startedAt') or ''):
            name = check.get('name') or check.get('context')
            checks[name] = (check.get('conclusion') or check.get('state') or '').upper()
        non_review = [v for k, v in checks.items() if k != 'Review gate']
        if pull.get('isDraft'):
            action, owner = 'prepare', record.get('implementer')
        elif pull.get('reviewDecision') == 'CHANGES_REQUESTED' or any(v.get('status') == 'fail' for v in current):
            action, owner = 'fix-review', record.get('implementer')
        elif not non_review or any(v not in ('SUCCESS', 'SKIPPED', 'NEUTRAL') for v in non_review):
            action, owner = 'check-ci', record.get('coordinator')
        elif reviewing:
            action, owner = 'await-review', record.get('coordinator')
        elif all(v.get('status') == 'pass' for v in current):
            action, owner = 'check-merge-gate', record.get('coordinator')
        else:
            action, owner = 'request-review', record.get('coordinator')
        rows.append({'number': number, 'head': head, 'next': action,
                     'owner': owner or 'unassigned', 'stateFresh': fresh})
    slots = max(0, limit - active)
    for row in rows:
        row['dispatchReview'] = row['next'] == 'request-review' and slots > 0
        if row['dispatchReview']:
            slots -= 1
    return {'activeReviews': active, 'reviewLimit': limit, 'pullRequests': rows}
