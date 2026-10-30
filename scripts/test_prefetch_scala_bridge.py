#!/usr/bin/env python3
"""Exercise bootstrap failures without network access or real backoff delays."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("prefetch_scala_bridge.sh")
MISSING = "[error] Missing org.scala-lang:scala2-sbt-bridge:2.13.18:compile"


class PrefetchScalaBridgeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="bridge bootstrap ")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.cache = self.root / "cache with spaces"
        self.bridges = [self.cache / "https" / host / "maven2/org/scala-lang/scala2-sbt-bridge"
                        for host in ("repo1.maven.org", "repo.maven.apache.org")]
        for bridge in self.bridges:
            bridge.mkdir(parents=True)
            (bridge / "cached-entry").write_text("existing")
        self.other = self.cache / "other-dependency.jar"
        self.other.write_text("keep")
        self.config = self.root / "config.json"
        self.journal = self.root / "journal.json"
        self.sleeps = self.root / "sleeps.json"
        self.executable("sbt", '''import json, os, sys
from pathlib import Path
assert sys.argv[1:] == ['--batch', 'show scalaCompilerBridgeBinaryJar']
config = json.loads(Path(os.environ['BRIDGE_TEST_CONFIG']).read_text())
p = Path(os.environ['BRIDGE_TEST_JOURNAL'])
calls = json.loads(p.read_text()) if p.exists() else []
calls.append([Path(b).exists() for b in config['bridges']])
p.write_text(json.dumps(calls))
status, message = config['results'][len(calls)-1]
print(message)
sys.exit(status)
''')
        self.executable("sleep", '''import json, os, sys
from pathlib import Path
p = Path(os.environ['BRIDGE_TEST_SLEEPS'])
calls = json.loads(p.read_text()) if p.exists() else []
calls.append(int(sys.argv[1]))
p.write_text(json.dumps(calls))
''')
        self.executable("curl", '''import os, sys
from pathlib import Path
out = Path(sys.argv[sys.argv.index("-o") + 1])
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(b"bridge")
url = next(a for a in sys.argv[1:] if a.startswith("http"))
p = Path(os.environ["BRIDGE_TEST_CURLS"])
prior = p.read_text() if p.exists() else ""
p.write_text(prior + url + "\\n")
''')
        self.curls = self.root / "curls.txt"

    def executable(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env python3\n" + body)
        path.chmod(0o755)

    def run_bootstrap(self, results):
        self.config.write_text(json.dumps({"bridges": list(map(str, self.bridges)), "results": results}))
        self.curls.write_text("")
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   COURSIER_CACHE=str(self.cache), IVY2_CACHE=str(self.root / "ivy2"),
                   BRIDGE_TEST_CONFIG=str(self.config),
                   BRIDGE_TEST_JOURNAL=str(self.journal), BRIDGE_TEST_SLEEPS=str(self.sleeps),
                   BRIDGE_TEST_CURLS=str(self.curls))
        result = subprocess.run(["bash", str(SCRIPT)], env=env, text=True, capture_output=True)
        self.assertEqual(self.other.read_text(), "keep")
        return result

    def calls(self):
        return json.loads(self.journal.read_text())

    def delays(self):
        return json.loads(self.sleeps.read_text()) if self.sleeps.exists() else []

    def test_success_preserves_cached_bridge(self):
        result = self.run_bootstrap([(0, "[info] bridge.jar")])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.calls(), [[True, True]])
        self.assertEqual(self.delays(), [])
        self.assertTrue(all(p.exists() for p in self.bridges))

    def test_known_miss_repairs_only_bridge_entries_then_succeeds(self):
        result = self.run_bootstrap([(17, MISSING), (0, "[info] bridge.jar")])
        self.assertEqual(result.returncode, 0)
        # First attempt sees the poisoned cache; the seed replaces it with a jar.
        self.assertEqual(self.calls()[0], [True, True])
        self.assertEqual(self.delays(), [5])
        self.assertIn(MISSING, result.stdout)
        self.assertIn("Seeded Scala bridge 2.13.18 from repo1.maven.org", result.stdout)
        jar = (self.bridges[0] / "2.13.18" / "scala2-sbt-bridge-2.13.18.jar")
        pom = (self.bridges[0] / "2.13.18" / "scala2-sbt-bridge-2.13.18.pom")
        self.assertTrue(jar.is_file())
        self.assertTrue(pom.is_file())
        curls = self.curls.read_text().splitlines()
        self.assertTrue(any(line.endswith(".jar") for line in curls))
        self.assertTrue(any(line.endswith(".pom") for line in curls))
        self.assertEqual(self.other.read_text(), "keep")

    def test_backoff_and_attempts_are_bounded(self):
        result = self.run_bootstrap([(17, MISSING)] * 3)
        self.assertEqual(result.returncode, 17)
        self.assertEqual(len(self.calls()), 3)
        self.assertEqual(self.delays(), [5, 10])

    def test_third_attempt_can_recover(self):
        result = self.run_bootstrap([(17, MISSING), (17, MISSING), (0, "[info] bridge.jar")])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(self.calls()), 3)
        self.assertEqual(self.delays(), [5, 10])

    def test_other_bootstrap_errors_fail_immediately(self):
        result = self.run_bootstrap([(42, "[error] invalid build definition")])
        self.assertEqual(result.returncode, 42)
        self.assertEqual(self.calls(), [[True, True]])
        self.assertEqual(self.delays(), [])
        self.assertTrue(all(p.exists() for p in self.bridges))


if __name__ == "__main__":
    unittest.main()
