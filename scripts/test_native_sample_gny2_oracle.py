#!/usr/bin/env python3
"""CPU-only rejection checks; these do not execute or qualify a GPU."""
import struct
import unittest

import native_sample_gny2_oracle as oracle


def fixture(command=37, samples=1):
    return struct.pack('<4096I', *oracle._expected_words(command, samples))


class Gny2OracleTests(unittest.TestCase):
    def test_complete_layout_and_fixed_recipe(self):
        layout = oracle._layout()
        self.assertEqual(len(layout), 628)
        occupied = {0, 1, 2}
        for _, word, bits, _ in layout:
            occupied.update(range(word, word + (bits + 31) // 32))
        self.assertEqual(len(occupied), 766)
        self.assertEqual(4096 - len(occupied), 3330)
        self.assertEqual(max(occupied), 778)
        for command in (1, 37, 255):
            for samples in (1, 2, 4):
                data = fixture(command, samples)
                self.assertIsNone(oracle.verify(data, command, samples))
                words = struct.unpack('<4096I', data)
                self.assertEqual(words[:3], (0x474e5902, 16384, 2))
                fields = {name: (word, bits) for name, word, bits, _ in layout}
                self.assertEqual(words[fields['commandId'][0]], command)
                self.assertEqual(words[fields['descriptorReadBytes'][0]], 3072)
                self.assertEqual(words[fields['drawReadBytes'][0]], 4096)
                self.assertEqual(words[fields['frame.images.images.pageReadLines'][0]], 1)
                self.assertEqual(words[fields['frame.images.images.recordReadLines'][0]], 23)
                self.assertEqual(words[fields['frame.images.images.storedBytes'][0]], 1088*samples)

    def test_every_used_and_reserved_word_mutation_rejected(self):
        # Exercise every one of the 766 used and 3330 reserved words, including
        # header, narrow high padding, zero fault trees and the entire tail.
        original = fixture()
        for word in range(4096):
            changed = bytearray(original)
            changed[word * 4] ^= 1
            with self.assertRaises(ValueError, msg=f'word {word}'):
                oracle.verify(bytes(changed), 37, 1)

    def test_every_field_high_bit_and_wide_counter_rejected(self):
        original = fixture()
        for name, word, bits, _ in oracle._layout():
            # Flip the highest allocated word's top bit, also rejecting
            # noncanonical sign extension and bits above a narrow field.
            changed = bytearray(original)
            offset = (word + (bits - 1) // 32) * 4 + 3
            changed[offset] ^= 0x80
            with self.assertRaises(ValueError, msg=name):
                oracle.verify(bytes(changed), 37, 1)
        for name in ('frame.receivedParts', 'frame.droppedParts'):
            word, bits = next((w, b) for n, w, b, _ in oracle._layout() if n == name)
            self.assertEqual(bits, 145)
            # A nonzero true counter bit above uint64 must not be truncated.
            for bit in (64, 96, 128, 144, 145, 159):
                changed = bytearray(original)
                changed[word*4 + bit//8] ^= 1 << (bit % 8)
                with self.assertRaises(ValueError, msg=f'{name} bit {bit}'):
                    oracle.verify(bytes(changed), 37, 1)

    def test_original_identity_samples_and_exact_wire(self):
        data = fixture()
        for command, samples in ((38, 1), (37, 2), (37, 4)):
            with self.assertRaises(ValueError):
                oracle.verify(data, command, samples)
        for value in (data[:-1], data+b'\0', b'', bytearray(data), None):
            with self.assertRaises(ValueError):
                oracle.verify(value, 37, 1)
        for command in (0, 256, -1, True, 1.0, '37'):
            with self.assertRaises(ValueError):
                oracle.verify(data, command, 1)
        for samples in (0, 3, 8, True, 1.0, '1'):
            with self.assertRaises(ValueError):
                oracle.verify(data, 37, samples)


if __name__ == '__main__':
    unittest.main()
