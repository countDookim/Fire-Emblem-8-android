import json
import subprocess
import unittest
from unittest.mock import patch

from tools import prune_actions_artifacts as prune


def artifact(artifact_id, run_id, name="other", expired=False):
    return {"id": artifact_id, "name": name, "expired": expired, "workflow_run": {"id": run_id}}


class PruneArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.run = {"id": 10, "head_branch": "master", "path": ".github/workflows/ci.yml",
                    "status": "completed", "conclusion": "success"}
        self.pages = [{"artifacts": [artifact(1, 5), artifact(2, 10, "results")]
                       + [artifact(100 + i, 11) for i in range(98)]},
                      {"artifacts": [artifact(3, 10, "dokka-site"), artifact(4, 11), artifact(5, 6)]}]

    def invoke(self, dry_run=False, env=None, delete_errors=None):
        responses = [subprocess.CompletedProcess([], 0, json.dumps(self.run), ""),
                     *[subprocess.CompletedProcess([], 0, json.dumps(page), "") for page in self.pages]]
        responses += delete_errors or [subprocess.CompletedProcess([], 0, "", "")] * 2
        with patch.dict("os.environ", env or {}, clear=True), \
                patch.object(prune.time, "sleep") as sleep, \
                patch.object(prune.subprocess, "run", side_effect=responses) as call:
            prune.prune("owner/repo", 10, dry_run)
            self.assertEqual(sleep.call_count, 0 if dry_run else max(0, len(call.call_args_list) - 4))
            return call.call_args_list

    def test_paginated_deletion_keeps_anchor_and_newer(self):
        calls = self.invoke()
        self.assertIn("page=1", calls[1].args[0][-1])
        self.assertIn("page=2", calls[2].args[0][-1])
        self.assertEqual([c.args[0][-1] for c in calls[3:]],
                         ["repos/owner/repo/actions/artifacts/1", "repos/owner/repo/actions/artifacts/5"])

    def test_dry_run_and_missing_anchor(self):
        self.assertEqual(len(self.invoke(dry_run=True)), 3)
        self.pages = [{"artifacts": [artifact(1, 5)]}]
        with self.assertRaisesRegex(ValueError, "lacks non-expired"):
            self.invoke()

    def test_expired_replacement_is_not_sufficient(self):
        self.pages[1]["artifacts"][0]["expired"] = True
        with self.assertRaisesRegex(ValueError, "lacks non-expired"):
            self.invoke()

    def test_invalid_and_current_anchor(self):
        self.run["status"] = "in_progress"
        with self.assertRaises(ValueError):
            self.invoke()
        env = {"GITHUB_RUN_ID": "10", "GITHUB_REPOSITORY": "owner/repo", "GITHUB_REF": "refs/heads/master"}
        self.assertEqual(len(self.invoke(dry_run=True, env=env)), 3)
        self.run["path"] = ".github/workflows/other.yml"
        with self.assertRaises(ValueError):
            self.invoke(env=env)

    def test_fetch_failure_prevents_deletion(self):
        responses = [subprocess.CompletedProcess([], 0, json.dumps(self.run), ""),
                     subprocess.CompletedProcess([], 0, json.dumps(self.pages[0]), ""),
                     subprocess.CompletedProcess([], 1, "", "page failed")]
        with patch.object(prune.subprocess, "run", side_effect=responses) as call:
            with self.assertRaisesRegex(RuntimeError, "page failed"):
                prune.prune("owner/repo", 10)
            self.assertEqual(call.call_count, 3)
            self.assertTrue(all("DELETE" not in c.args[0] for c in call.call_args_list))

    def test_delete_404_and_other_failure(self):
        absent = subprocess.CompletedProcess([], 1, "", "Not Found (HTTP 404)")
        failed = subprocess.CompletedProcess([], 1, "", "Forbidden (HTTP 403)")
        with self.assertRaisesRegex(RuntimeError, "403"):
            self.invoke(delete_errors=[absent, failed])

if __name__ == "__main__":
    unittest.main()
