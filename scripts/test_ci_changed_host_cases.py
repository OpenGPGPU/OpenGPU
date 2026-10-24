#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile
import unittest

from ci_changed_host_cases import added_test_bodies, narrow
from ci_case_partitions import plan


SOURCE = r'''package opengpu.graphics
abstract class BaseSpec extends AnyFlatSpec {
  def profile = 2
  it should s"render local=$local" in {
    val text = "braces { } and escaped \" quotes"
    simulate {
      check()
    }
  }
  it should "reset" in {
    checkReset()
  }
}
class FirstHostAxiSpec extends BaseSpec
class SecondHostAxiSpec extends BaseSpec
'''


class ChangedHostCasesTest(unittest.TestCase):
    def test_only_additions_inside_existing_bodies_qualify(self):
        source = SOURCE.replace('      check()', '      check()\n      assert(valid)')
        self.assertEqual(added_test_bodies(source, '@@ -7,0 +8 @@\n+      assert(valid)'), [(4, 4, 10)])
        for diff in ('@@ -3 +3 @@\n-profile\n+profile2',
                     '@@ -2,0 +3 @@\n+fixture',
                     '@@ -3,0 +4 @@\n+registration',
                     '@@ -9,0 +10 @@\n+end', ''):
            self.assertIsNone(added_test_bodies(source, diff))

    def test_comments_and_multiline_strings_do_not_close_a_body(self):
        source = 'it should "a" in {\n val s = """} {\n}"""\n /* } */ check()\n}\n'
        self.assertEqual(added_test_bodies(source, '@@ -2,0 +4 @@\n+check()'), [(1, 1, 5)])
        self.assertIsNone(added_test_bodies(source.replace('/* } */', '/* /* } */ */'), '@@ -2,0 +4 @@'))

    def test_real_git_selection_preserves_consumers_and_falls_back_for_fixtures(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
            git('init', '-q')
            git('config', 'user.email', 'test@example.com')
            git('config', 'user.name', 'Test')
            path = root / 'src/test/scala/opengpu/graphics/ExampleHostAxiSpec.scala'
            path.parent.mkdir(parents=True)
            path.write_text(SOURCE)
            git('add', '.')
            git('commit', '-qm', 'baseline')
            base = git('rev-parse', 'HEAD')
            path.write_text(SOURCE.replace('      check()', '      check()\n      assert(valid)'))
            git('commit', '-qam', 'assertion')
            suites = ['opengpu.graphics.' + name + 'HostAxiSpec' for name in ('First', 'Second')]
            catalog = {suite: ['should render local=true', 'should render local=false', 'should reset'] for suite in suites}
            locations = {(suite, name): (path.name, 4 if 'render' in name else 11)
                         for suite, names in catalog.items() for name in names}
            selected, filtered, exact = narrow(suites, catalog, root, base, locations)
            self.assertEqual(selected, suites)
            self.assertEqual(exact, set(suites))
            self.assertTrue(all(names == catalog[suite][:2] for suite, names in filtered.items()))
            partitions = plan(selected, filtered, exact)
            self.assertEqual(sorted((part['suites'][0], name) for part in partitions for name in part['cases']),
                             sorted((suite, name) for suite in suites for name in catalog[suite][:2]))
            # A derived profile without this inherited test need not run its reset scenario.
            locations[suites[1], 'should render local=true'] = (path.name, 11)
            locations[suites[1], 'should render local=false'] = (path.name, 11)
            self.assertEqual(narrow(suites, catalog, root, base, locations)[0], suites[:1])
            # Unknown locations, fixture edits and matching production changes keep whole suites.
            self.assertEqual(narrow(suites, catalog, root, base, {})[0], suites)
            self.assertEqual(narrow(suites, catalog, root, base, {})[2], set())
            production = root / 'src/main/scala/opengpu/graphics/ExampleHostAxi.scala'
            production.parent.mkdir(parents=True)
            production.write_text('package opengpu.graphics\nclass ExampleHostAxi\n')
            git('add', '.')
            git('commit', '-qm', 'production')
            self.assertEqual(narrow(suites, catalog, root, base, locations)[1], catalog)
            production.unlink()
            git('commit', '-qam', 'remove production')
            path.write_text(path.read_text().replace('def profile = 2', 'def profile = 4'))
            git('commit', '-qam', 'fixture')
            selected, filtered, exact = narrow(suites, catalog, root, base, locations)
            self.assertEqual(selected, suites)
            self.assertEqual(filtered, catalog)
            self.assertFalse(exact)


if __name__ == '__main__':
    unittest.main()
