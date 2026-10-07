// chart_path_utils.h — pure C++17, no wx/Qt/Android dependencies.
//
// Host-testable logic extracted from the Android chart/storage path handling:
//  - parsing the ";"-separated contract produced by QtActivity.getSystemDirs()
//  - Storage Access Framework tree-URI helpers
//  - CM93 dataset directory validation (mirrors FindCM93Dictionary in
//    gui/src/cm93.cpp: a dataset is recognized by a cm93obj.dic file found
//    via case-insensitive depth-first traversal)
//
// SPDX-License-Identifier: GPL-2.0-or-later

#ifndef OCPN_ANDROID_CHART_PATH_UTILS_H
#define OCPN_ANDROID_CHART_PATH_UTILS_H

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace ocpn {
namespace android {

struct SystemDirs {
  bool app_on_external = false;
  std::string files_dir;
  std::string cache_dir;
  std::string ext_files_dir;
  std::string ext_cache_dir;
  std::string shared_root;
};

inline std::vector<std::string> Split(const std::string& s, char delim) {
  std::vector<std::string> out;
  std::string cur;
  for (char c : s) {
    if (c == delim) {
      out.push_back(cur);
      cur.clear();
    } else {
      cur.push_back(c);
    }
  }
  out.push_back(cur);
  return out;
}

/**
 * Parse the string produced by QtActivity.getSystemDirs():
 *   "EXTAPP|INTAPP;<files>;<cache>;<extFiles>;<extCache>;<sharedRoot>;"
 * Missing/empty fields are tolerated (older builds, unavailable volumes).
 */
inline SystemDirs ParseSystemDirs(const std::string& raw) {
  SystemDirs d;
  auto parts = Split(raw, ';');
  if (!parts.empty()) d.app_on_external = (parts[0] == "EXTAPP");
  if (parts.size() > 1) d.files_dir = parts[1];
  if (parts.size() > 2) d.cache_dir = parts[2];
  if (parts.size() > 3) d.ext_files_dir = parts[3];
  if (parts.size() > 4) d.ext_cache_dir = parts[4];
  if (parts.size() > 5) d.shared_root = parts[5];
  return d;
}

/** True for Storage Access Framework tree URIs from ACTION_OPEN_DOCUMENT_TREE. */
inline bool IsSafTreeUri(const std::string& uri) {
  return uri.rfind("content://", 0) == 0 && uri.find("/tree/") != std::string::npos;
}

/** Percent-decode (minimal: %XX sequences only). */
inline std::string PercentDecode(const std::string& s) {
  std::string out;
  for (size_t i = 0; i < s.size(); ++i) {
    if (s[i] == '%' && i + 2 < s.size() &&
        std::isxdigit((unsigned char)s[i + 1]) &&
        std::isxdigit((unsigned char)s[i + 2])) {
      out.push_back((char)std::stoi(s.substr(i + 1, 2), nullptr, 16));
      i += 2;
    } else {
      out.push_back(s[i]);
    }
  }
  return out;
}

/**
 * Extract the document id from a SAF tree URI, e.g.
 *   content://com.android.externalstorage.documents/tree/primary%3ADownload
 * -> "primary:Download". Returns "" if not a tree URI.
 */
inline std::string SafTreeDocId(const std::string& uri) {
  auto pos = uri.find("/tree/");
  if (pos == std::string::npos) return "";
  std::string doc = uri.substr(pos + 6);
  auto q = doc.find('?');
  if (q != std::string::npos) doc = doc.substr(0, q);
  return PercentDecode(doc);
}

enum class Cm93Validation {
  Ok,
  NotFound,          // path does not exist
  NotDirectory,      // path exists but is not a directory
  MissingDictionary, // no cm93obj.dic found (not a CM93 dataset)
  EmptyDataset,      // dictionary found but no cell coverage dirs
};

inline std::string ToString(Cm93Validation v) {
  switch (v) {
    case Cm93Validation::Ok: return "Ok";
    case Cm93Validation::NotFound: return "NotFound";
    case Cm93Validation::NotDirectory: return "NotDirectory";
    case Cm93Validation::MissingDictionary: return "MissingDictionary";
    case Cm93Validation::EmptyDataset: return "EmptyDataset";
  }
  return "?";
}

inline std::string ToLower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(),
                 [](unsigned char c) { return (char)std::tolower(c); });
  return s;
}

/**
 * Validate a candidate CM93 dataset directory.
 * Mirrors the native discovery (FindCM93Dictionary): the dataset root is
 * accepted when a "cm93obj.dic" file (any case) is found at depth <= 4, and
 * at least one cell directory (8-digit name, e.g. 00300000) exists.
 * Symlink loops and permission errors are tolerated, never fatal.
 */
inline Cm93Validation ValidateCm93Directory(const std::string& path,
                                            int max_depth = 4) {
  namespace fs = std::filesystem;
  std::error_code ec;
  if (!fs::exists(path, ec)) return Cm93Validation::NotFound;
  if (!fs::is_directory(path, ec)) return Cm93Validation::NotDirectory;

  bool found_dic = false;
  bool found_cell = false;

  std::function<void(const fs::path&, int)> walk =
      [&](const fs::path& dir, int depth) {
        if (depth > max_depth || (found_dic && found_cell)) return;
        fs::directory_iterator it(dir, ec);
        if (ec) return;
        for (const auto& entry : it) {
          if (found_dic && found_cell) break;
          std::error_code ec2;
          std::string name = ToLower(entry.path().filename().string());
          if (entry.is_regular_file(ec2)) {
            if (name == "cm93obj.dic") found_dic = true;
          } else if (entry.is_directory(ec2)) {
            // CM93 v2 cell dirs are 8-digit, e.g. "00300000"
            if (name.size() == 8 &&
                std::all_of(name.begin(), name.end(), ::isdigit)) {
              found_cell = true;
            } else {
              walk(entry.path(), depth + 1);
            }
          }
        }
      };

  walk(fs::path(path), 0);

  if (!found_dic) return Cm93Validation::MissingDictionary;
  if (!found_cell) return Cm93Validation::EmptyDataset;
  return Cm93Validation::Ok;
}

}  // namespace android
}  // namespace ocpn

#endif  // OCPN_ANDROID_CHART_PATH_UTILS_H
