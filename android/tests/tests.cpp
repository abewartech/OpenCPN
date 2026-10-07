// tests.cpp — minimal dependency-free test runner for android/tests.
// Build: g++ -std=c++17 -I. tests.cpp -o android_tests && ./android_tests
// SPDX-License-Identifier: GPL-2.0-or-later

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>

#include "chart_path_utils.h"

namespace fs = std::filesystem;
using ocpn::android::Cm93Validation;
using ocpn::android::IsSafTreeUri;
using ocpn::android::ParseSystemDirs;
using ocpn::android::PercentDecode;
using ocpn::android::SafTreeDocId;
using ocpn::android::ToString;
using ocpn::android::ValidateCm93Directory;

static int g_pass = 0, g_fail = 0;

#define CHECK(cond)                                                      \
  do {                                                                   \
    if (cond) {                                                          \
      ++g_pass;                                                          \
    } else {                                                             \
      ++g_fail;                                                          \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);         \
    }                                                                    \
  } while (0)

#define CHECK_EQ(a, b) CHECK((a) == (b))

static void TestParseSystemDirs() {
  auto d = ParseSystemDirs(
      "EXTAPP;/data/user/0/org.opencpn.opencpn/files;"
      "/data/user/0/org.opencpn.opencpn/cache;"
      "/storage/emulated/0/Android/data/org.opencpn.opencpn/files;"
      "/storage/emulated/0/Android/data/org.opencpn.opencpn/cache;"
      "/storage/emulated/0;");
  CHECK(d.app_on_external);
  CHECK_EQ(d.files_dir, "/data/user/0/org.opencpn.opencpn/files");
  CHECK_EQ(d.cache_dir, "/data/user/0/org.opencpn.opencpn/cache");
  CHECK_EQ(d.ext_files_dir,
           "/storage/emulated/0/Android/data/org.opencpn.opencpn/files");
  CHECK_EQ(d.ext_cache_dir,
           "/storage/emulated/0/Android/data/org.opencpn.opencpn/cache");
  CHECK_EQ(d.shared_root, "/storage/emulated/0");

  auto d2 = ParseSystemDirs("INTAPP;/a;/b;");
  CHECK(!d2.app_on_external);
  CHECK_EQ(d2.files_dir, "/a");
  CHECK_EQ(d2.cache_dir, "/b");
  CHECK_EQ(d2.shared_root, "");  // tolerated, not fatal

  auto d3 = ParseSystemDirs("");
  CHECK(!d3.app_on_external);
  CHECK_EQ(d3.files_dir, "");
}

static void TestSafUris() {
  const std::string tree =
      "content://com.android.externalstorage.documents/tree/primary%3ADownload";
  CHECK(IsSafTreeUri(tree));
  CHECK_EQ(SafTreeDocId(tree), "primary:Download");

  const std::string tree2 =
      "content://com.android.externalstorage.documents/tree/1234-5678%3ACharts"
      "?foo=bar";
  CHECK(IsSafTreeUri(tree2));
  CHECK_EQ(SafTreeDocId(tree2), "1234-5678:Charts");  // removable SD volume

  CHECK(!IsSafTreeUri("/storage/emulated/0/Charts"));
  CHECK(!IsSafTreeUri("file:///sdcard/Charts"));
  CHECK_EQ(SafTreeDocId("/storage/emulated/0/Charts"), "");

  CHECK_EQ(PercentDecode("a%2Fb%20c"), "a/b c");
  CHECK_EQ(PercentDecode("plain"), "plain");
}

static fs::path MakeTempDir(const std::string& name) {
  fs::path p = fs::temp_directory_path() / ("ocpn_test_" + name);
  fs::remove_all(p);
  fs::create_directories(p);
  return p;
}

static void TestValidateCm93() {
  // Valid dataset: dictionary (mixed case) + one 8-digit cell dir.
  {
    fs::path root = MakeTempDir("cm93_ok");
    std::ofstream(root / "CM93OBJ.DIC") << "dummy";
    fs::create_directories(root / "00300000" / "A");
    std::ofstream(root / "00300000" / "A" / "00300000.A") << "dummy";
    CHECK(ValidateCm93Directory(root.string()) == Cm93Validation::Ok);
    fs::remove_all(root);
  }
  // Dictionary nested one level deeper is still found (depth <= 4).
  {
    fs::path root = MakeTempDir("cm93_nested");
    fs::create_directories(root / "sub");
    std::ofstream(root / "sub" / "cm93obj.dic") << "dummy";
    fs::create_directories(root / "12345678");
    CHECK(ValidateCm93Directory(root.string()) == Cm93Validation::Ok);
    fs::remove_all(root);
  }
  // Missing dictionary -> MissingDictionary.
  {
    fs::path root = MakeTempDir("cm93_nodic");
    fs::create_directories(root / "00300000");
    CHECK(ValidateCm93Directory(root.string()) ==
          Cm93Validation::MissingDictionary);
    fs::remove_all(root);
  }
  // Dictionary but no cell dirs -> EmptyDataset.
  {
    fs::path root = MakeTempDir("cm93_empty");
    std::ofstream(root / "cm93obj.dic") << "dummy";
    fs::create_directories(root / "docs");
    CHECK(ValidateCm93Directory(root.string()) == Cm93Validation::EmptyDataset);
    fs::remove_all(root);
  }
  // Nonexistent path -> NotFound.
  CHECK(ValidateCm93Directory("/nonexistent_ocpn_test_dir_xyz") ==
        Cm93Validation::NotFound);
  // Regular file -> NotDirectory.
  {
    fs::path root = MakeTempDir("cm93_file");
    fs::path f = root / "afile";
    std::ofstream(f) << "x";
    CHECK(ValidateCm93Directory(f.string()) == Cm93Validation::NotDirectory);
    fs::remove_all(root);
  }
  // ToString coverage.
  CHECK_EQ(ToString(Cm93Validation::Ok), "Ok");
  CHECK_EQ(ToString(Cm93Validation::MissingDictionary), "MissingDictionary");
}

int main() {
  TestParseSystemDirs();
  TestSafUris();
  TestValidateCm93();
  std::printf("android unit tests: %d passed, %d failed\n", g_pass, g_fail);
  return g_fail == 0 ? 0 : 1;
}
