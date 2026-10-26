#!/usr/bin/env python3
"""Regression coverage for implicit Kotlin imports that a lexical unused-import scan misses."""
import tempfile
import unittest
from pathlib import Path
from check_import_contracts import ROOT, Source, check_extension_contracts


class ImplicitImportTest(unittest.TestCase):
    def check(self, code):
        with tempfile.TemporaryDirectory(dir=ROOT, prefix="import-contract-fixture-") as directory:
            path = Path(directory) / "Example.kt"
            path.write_text("package example\n" + code)
            return check_extension_contracts([Source(path)])

    def test_read_delegate_requires_get_value(self):
        errors = self.check("import androidx.compose.runtime.mutableStateOf\nval state by mutableStateOf(1)")
        self.assertEqual(len(errors), 1)
        self.assertIn("runtime.getValue", errors[0])

    def test_write_delegate_requires_set_value(self):
        errors = self.check("import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.getValue\nvar state by mutableStateOf(1)")
        self.assertEqual(len(errors), 1)
        self.assertIn("runtime.setValue", errors[0])

    def test_implicit_imports_pass_when_present(self):
        errors = self.check("import androidx.compose.runtime.*\nimport androidx.compose.ui.unit.dp\nvar state by mutableStateOf(1)\nval width = 760.dp")
        self.assertEqual(errors, [])

    def test_dp_literal_requires_extension(self):
        errors = self.check("val width = 760.dp")
        self.assertEqual(len(errors), 1)
        self.assertIn("unit.dp", errors[0])

    def test_comments_and_strings_do_not_trigger_delegate_checks(self):
        self.assertEqual(self.check('import androidx.compose.runtime.Composable\n// var state by ignored\nval text = "760.dp"'), [])


if __name__ == "__main__":
    unittest.main()
