import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pr_queue import queue_snapshot


class QueueTest(unittest.TestCase):
    def pull(self, n=1):
        return dict(number=n, headRefOid='a', statusCheckRollup=[dict(name='tests', conclusion='SUCCESS')])

    def state(self, n=1, status='pass', head='a'):
        return {'pullRequests': {str(n): {'head': head, 'coordinator': 'lead', 'reviews': {
            axis: dict(head=head, status=status) for axis in ('Spec', 'Standards')}}}}

    def test_old_head_cannot_authorize_next_merge_step(self):
        result = queue_snapshot([self.pull()], self.state(head='old'))['pullRequests'][0]
        self.assertEqual('request-review', result['next'])
        self.assertFalse(result['stateFresh'])

    def test_full_review_still_requires_real_merge_gate(self):
        self.assertEqual('check-merge-gate', queue_snapshot([self.pull()], self.state())['pullRequests'][0]['next'])

    def test_review_budget_includes_already_running_reviews(self):
        pulls = [self.pull(n) for n in range(1, 7)]
        result = queue_snapshot(pulls, self.state(status='in-progress'))
        self.assertEqual(1, result['activeReviews'])
        self.assertEqual(2, sum(r['dispatchReview'] for r in result['pullRequests']))

    def test_failed_ci_blocks_dispatch(self):
        p = self.pull()
        p['statusCheckRollup'][0]['conclusion'] = 'FAILURE'
        self.assertEqual('check-ci', queue_snapshot([p], {})['pullRequests'][0]['next'])

    def test_latest_success_replaces_historical_failure(self):
        p = self.pull()
        p['statusCheckRollup'] = [dict(name='tests', conclusion=c, completedAt=t)
                                 for c, t in [('SUCCESS', '2026-02'), ('FAILURE', '2026-01')]]
        self.assertTrue(queue_snapshot([p], {})['pullRequests'][0]['dispatchReview'])

    def test_missing_checks_are_unknown(self):
        p = self.pull(); p['statusCheckRollup'] = []
        self.assertEqual('check-ci', queue_snapshot([p], {})['pullRequests'][0]['next'])

    def test_unreadable_receipt_fails(self):
        with self.assertRaises(ValueError):
            queue_snapshot([self.pull()], {'pullRequests': []})

    def test_old_head_review_keeps_slot_until_executor_stops(self):
        state = {'pullRequests': {}}
        for n in range(1, 4):
            state['pullRequests'].update(self.state(n, status='in-progress', head='old')['pullRequests'])
        pulls = [self.pull(n) for n in range(1, 5)]
        result = queue_snapshot(pulls, state)
        self.assertEqual(3, result['activeReviews'])
        self.assertFalse(any(r['dispatchReview'] for r in result['pullRequests']))
        state['pullRequests']['1']['reviews'] = {}
        result = queue_snapshot(pulls, state)
        self.assertEqual(2, result['activeReviews'])
        self.assertEqual(1, sum(r['dispatchReview'] for r in result['pullRequests']))

    def test_live_rerun_zero_completion_date_blocks_old_success(self):
        p = self.pull()
        p['statusCheckRollup'] = [
            dict(name='tests', status='IN_PROGRESS', conclusion='',
                 startedAt='2026-02', completedAt='0001-01-01T00:00:00Z'),
            dict(name='tests', status='COMPLETED', conclusion='SUCCESS', completedAt='2026-01')]
        row = queue_snapshot([p], {})['pullRequests'][0]
        self.assertEqual('check-ci', row['next'])
        self.assertFalse(row['dispatchReview'])
