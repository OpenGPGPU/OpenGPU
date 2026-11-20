"""CPU checks for the P11 ordinary L1 cached-alias publication contract."""
import struct
import unittest

import compute_l1_publication as protocol
import compute_l1_publication_cases as cases
import qualify_compute_owner as qualifier

HELD = dict(commandId=17)


class L1PublicationCases(unittest.TestCase):
    def test_roles_and_words(self):
        self.assertEqual([cases.parameters(c)['role'] for c in cases.CASES],
                         ['warm', 'producer', 'reload'])
        self.assertEqual(cases.DATA_WORD, 7)
        self.assertEqual(cases.FLAG_WORD, 1)
        self.assertEqual(cases.parameters(cases.CASES[0])['data_word'], 0)

    def test_sources_are_distinct_and_bounded(self):
        words = [cases.bound_words(c) for c in cases.CASES]
        self.assertEqual(len(words[0]), len(words[2]))
        self.assertNotEqual(words[0], words[2])
        self.assertNotEqual(words[0], words[1])
        for program in words:
            self.assertTrue(all(type(word) is int for word in program))
            self.assertFalse(len(program) % 16)
            self.assertLessEqual(len(program) * 4, 4096)
        arg = cases.initial_kernarg(cases.CASES[0])
        self.assertEqual(len(arg), 8192)
        self.assertEqual(struct.unpack_from('<I', arg, cases.DATA_OFFSET)[0], 0)

    def test_same_gem_aliases(self):
        self.assertEqual(cases.CACHED_BASE, 0x80800000)
        self.assertEqual(cases.STORAGE_BASE, 0xcfffc000)


class L1PublicationProtocol(unittest.TestCase):
    def test_plan(self):
        self.assertEqual(struct.unpack('<8sIIQ', protocol.matrix_plan()),
                         (b'OGCLPM01', 3, 1, protocol.BASE))
        self.assertEqual(len(protocol.PHASES), 3)
        self.assertEqual([p['role'] for p in protocol.PHASES], ['warm', 'producer', 'reload'])

    def test_target_plan(self):
        description = {'sourceCase': cases.CASES[0],
                       'kernarg': {'allocationPhysical': 0x01001000}}
        self.assertEqual(struct.unpack('<8sQ', protocol.target_plan(description, 0)),
                         (b'OGCLPT01', 0x01001000))

    def test_round_identity(self):
        first = dict(schema=protocol.ROUND_SCHEMA, phase=0, role='warm', commandId=17,
                     resetCount=1, ramReplaced=False, externalDrained=True,
                     sourceQualificationRequired=True, independentBytesRequired=True,
                     startCycle=10, endCycle=20)
        self.assertEqual(protocol.check_round(first, 0, HELD)['commandId'], 17)
        with self.assertRaises(ValueError):
            protocol.check_round(dict(first, commandId=18), 0, HELD)

    def test_cache_witness_order(self):
        record = dict(schema=protocol.CACHE_SCHEMA, commandId=17, phase=2,
                      targetLine=0x01000000, startCycle=10, endCycle=100,
                      warmL1Mask=3, warmL2=True, invalidateMask=3, invalidateAckMask=3,
                      refilledL1Mask=3, refilledL2=True,
                      ordinaryL1AliasRequired=True, uncachedStoragePath=True,
                      warmCycle=20, invalidateCycle=30, invalidateAckCycle=40,
                      firstTargetWriteCycle=50, reloadReadRequestCycle=60,
                      reloadReadResponseCycle=70, refillCycle=80)
        description = {'sourceCase': cases.CASES[2],
                       'kernarg': {'allocationPhysical': 0x01000000}}
        self.assertEqual(protocol.check_cache(record, 2, description, HELD)['commandId'], 17)
        with self.assertRaises(ValueError):
            protocol.check_cache(dict(record, firstTargetWriteCycle=35), 2, description, HELD)


class L1PublicationWiring(unittest.TestCase):
    def test_qualifier_wiring(self):
        self.assertEqual(qualifier.L1_PUBLICATION_CASES, cases.CASES)
        self.assertTrue(set(cases.CASES).issubset(set(qualifier.CASES)))


if __name__ == '__main__':
    unittest.main()
