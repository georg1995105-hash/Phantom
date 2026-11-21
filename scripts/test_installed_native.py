"""Regression tests: Android can omit nativeLibraryDir from dumpsys package."""
import unittest

from ci_verify_installed_native import installed_native_dir


class InstalledNativeDirectoryTest(unittest.TestCase):
    def test_android15_without_native_library_dir_uses_installed_base_apk(self):
        package_dump = "Packages:\n    primaryCpuAbi=x86\n"
        package_paths = ("package:/data/app/~~TOKEN/xyz.wraithnet.phantom-ABCDE/base.apk\n")
        abi, directory = installed_native_dir(package_dump, package_paths)
        self.assertEqual("x86", abi)
        self.assertEqual(
            "/data/app/~~TOKEN/xyz.wraithnet.phantom-ABCDE/lib/x86", directory
        )

    def test_explicit_native_library_dir_is_preferred(self):
        dump = ("primaryCpuAbi=arm64-v8a\n"
                "nativeLibraryDir=/data/app/example/lib/arm64\n")
        self.assertEqual(
            ("arm64-v8a", "/data/app/example/lib/arm64"),
            installed_native_dir(dump, "garbage"),
        )

    def test_arm32_uses_legacy_arm_directory_name(self):
        abi, directory = installed_native_dir(
            "primaryCpuAbi=armeabi-v7a",
            "package:/data/app/example/base.apk",
        )
        self.assertEqual("armeabi-v7a", abi)
        self.assertEqual("/data/app/example/lib/arm", directory)

    def test_unresolvable_install_fails_closed(self):
        with self.assertRaisesRegex(AssertionError, "base.apk"):
            installed_native_dir("primaryCpuAbi=x86", "")
        with self.assertRaisesRegex(AssertionError, "primaryCpuAbi"):
            installed_native_dir("", "package:/data/app/example/base.apk")


if __name__ == "__main__":
    unittest.main()
