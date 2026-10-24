#!/usr/bin/env python3
"""Substitute checked native-host witnesses for passive telemetry wrapper suites.

Only exact output additions and the native counter-source selection qualify.
Behavioral edits, changed fixtures or absent exact host witnesses keep the full
suite. This policy applies only to PRs; the regression catalog is unchanged.
"""
import re
import subprocess
import sys

from changed_test_suites import changed_suites

GRAPHICS = "src/main/scala/opengpu/graphics/"
TESTS = "src/test/scala/opengpu/graphics/"
SYSTEM = "src/main/scala/opengpu/system/GpuHostSystemAxi.scala"
TYPED = "opengpu.graphics.NativeTypedGraphicsHostAxiSpec"
BINNED = "opengpu.graphics.NativeBinnedGraphicsHostAxiSpec"
WITNESSES = {
    TYPED: "should execute GPU-memory triangle line and point shader draws with translated resources and cached image consumers local=",
    BINNED: "should discover GNC1 submit translated multi-tile shaders and publish exact cached source and resolve images local=",
}
WRAPPERS = {
    "MemoryNativeBinnedGraphicsPipeline": (BINNED,),
    "TypedReplayTileImagePipeline": (BINNED,),
    "TypedTileImagePipeline": (TYPED, BINNED),
    "TypedTileRenderPipeline": (TYPED, BINNED),
}
OLD_COUNTER = "val fragmentEvents = binned.map(_.io.fragmentEvents).getOrElse(host.io.performance.fragment)"
NEW_COUNTER = ("val fragmentEvents = typed.map(_.io.fragmentEvents).orElse(nativeBinned.map(_.io.fragmentEvents))\n"
               "        .orElse(binned.map(_.io.fragmentEvents)).getOrElse(host.io.performance.fragment)")


def passive_forwarding(before, after):
    declaration = re.compile(r"^    val fragmentEvents=Output\(new FragmentPerformanceEvents\)\n", re.M)
    assignment = re.compile(r"^  io\.fragmentEvents:=\w+\.io\.fragmentEvents\n", re.M)
    if "fragmentEvents" in before:
        return False
    stripped, declarations = declaration.subn("", after)
    stripped, assignments = assignment.subn("", stripped)
    return declarations == assignments == 1 and stripped == before


def counter_source_only(before, after):
    return (before.count(OLD_COUNTER) == after.count(NEW_COUNTER) == 1
            and after.replace(NEW_COUNTER, OLD_COUNTER) == before)


def names_only(before, after):
    registration = re.compile(r'^(\s*it should )s?"(?:\\.|[^"\\])*"(?= in \{)', re.M)
    old, count = registration.subn(r'\1"<name>"', before)
    new, new_count = registration.subn(r'\1"<name>"', after)
    return count > 0 and count == new_count and old == new


def narrow(selection, catalog, exact, root, base):
    def source(revision, path):
        try:
            return subprocess.check_output(['git', 'show', f'{revision}:{path}'],
                                           cwd=root, text=True, stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            return None

    def covered(witnesses):
        return all(suite in selection and suite in exact and
                   all(WITNESSES[suite] + locality in catalog.get(suite, [])
                       for locality in ('false', 'true')) for suite in witnesses)

    deferred = set()
    passive_paths = set()
    for module, witnesses in WRAPPERS.items():
        suite = 'opengpu.graphics.' + module + 'Spec'
        if suite not in selection or not covered(witnesses):
            continue
        path = GRAPHICS + module + '.scala'
        before, after = source(base, path), source('HEAD', path)
        test = TESTS + module + 'Spec.scala'
        old_test, new_test = source(base, test), source('HEAD', test)
        if (None not in (before, after, old_test, new_test)
                and passive_forwarding(before, after)
                and (old_test == new_test or names_only(old_test, new_test))):
            deferred.add(suite)
            passive_paths.update((path, test))
    if covered((TYPED, BINNED)):
        before, after = source(base, SYSTEM), source('HEAD', SYSTEM)
        test = 'src/test/scala/opengpu/system/GpuHostSystemAxiSpec.scala'
        old_test, new_test = source(base, test), source('HEAD', test)
        if (None not in (before, after, old_test, new_test)
                and counter_source_only(before, after) and old_test == new_test):
            deferred.update(('opengpu.system.GpuHostSystemAxiSpec',
                             'opengpu.system.BufferedCommandGpuHostSystemAxiSpec'))
            passive_paths.update((SYSTEM, test))
    # A shared test fixture or another changed source may independently require
    # the same suite. Never erase that separate selection reason.
    if deferred:
        changed = subprocess.check_output(['git', 'diff', '--name-only', base, 'HEAD'],
                                          cwd=root, text=True).splitlines()
        deferred.difference_update(changed_suites(root, [path for path in changed if path not in passive_paths]))
    if deferred:
        print("PR telemetry coverage: native-host witnesses replace passive wrapper reruns: "
              + ", ".join(sorted(deferred)) + ". Complete suites remain in nightly/manual regression.",
              file=sys.stderr)
    return [suite for suite in selection if suite not in deferred]
