import hashlib
import json
from pathlib import Path
import unittest
import zipfile
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "docs/library-quality/fixtures"


class LibraryFixturesTest(unittest.TestCase):
    def test_all_import_scenarios_have_real_assets_and_expected_references(self):
        self.assertTrue((FIXTURES / "manifest.json").is_file(), "Import fixtures are not generated")
        manifest = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual({f"I{i:02}" for i in range(1, 15)}, {case["id"] for case in manifest["cases"]})
        for item in manifest["files"]:
            path = FIXTURES / item["path"]
            self.assertTrue(path.is_file())
            self.assertEqual(item["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        for case in manifest["cases"]:
            self.assertTrue(case["files"])
            self.assertTrue(case["expectation"])

    def test_docx_contains_actual_omml_and_table(self):
        self.assertTrue((FIXTURES / "i05-formulas.docx").is_file())
        with zipfile.ZipFile(FIXTURES / "i05-formulas.docx") as archive:
            xml = archive.read("word/document.xml").decode()
        self.assertIn("m:oMath", xml)
        self.assertIn("m:f", xml)
        self.assertIn("w:tbl", xml)
        self.assertNotIn("s(0.5s+1)", xml, "E05 is a polynomial stability task, not a transfer function")

    def test_pdf_page_counts_scan_and_encryption(self):
        self.assertTrue((FIXTURES / "i08-six-pages.pdf").is_file())
        self.assertEqual(6, len(PdfReader(FIXTURES / "i08-six-pages.pdf").pages))
        self.assertEqual(2, len(PdfReader(FIXTURES / "i03-two-pages.pdf").pages))
        scan = PdfReader(FIXTURES / "i04-scan.pdf")
        self.assertEqual(2, len(scan.pages))
        self.assertTrue(all(not page.extract_text().strip() for page in scan.pages))
        self.assertTrue(PdfReader(FIXTURES / "i13-protected.pdf").is_encrypted)


if __name__ == "__main__":
    unittest.main()
