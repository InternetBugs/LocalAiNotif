#!/usr/bin/env python3
"""Prepare metadata files and release notes for Xposed-Modules-Repo sync."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

DEFAULT_SUMMARY = (
    "面向 HyperOS 4 的本地端侧大模型智能通知模块，"
    "支持 AI 摘要、单条长通知概括、重要通知专区、噪音过滤与减少干扰。\n"
)

ALERT_LABELS = {
    "IMPORTANT": "重要提示",
    "NOTE": "说明",
    "TIP": "提示",
    "WARNING": "注意",
    "CAUTION": "警告",
}


def convert_github_alerts(text: str) -> str:
    """Convert GitHub-only alert syntax (> [!NOTE]) to portable Markdown blockquotes."""
    lines = text.splitlines()
    out: list[str] = []
    i = 0
    while i < len(lines):
        m = re.match(r"^>\s*\[!(IMPORTANT|NOTE|TIP|WARNING|CAUTION)\]\s*$", lines[i])
        if m:
            label = ALERT_LABELS[m.group(1)]
            if i + 1 < len(lines) and lines[i + 1].startswith(">"):
                next_body = re.sub(r"^>\s?", "", lines[i + 1])
                if next_body.startswith("- "):
                    out.append(f"> **{label}**")
                    out.append(lines[i + 1])
                else:
                    out.append(f"> **{label}**：{next_body}")
                i += 2
                continue
            out.append(f"> **{label}**")
            i += 1
            continue
        out.append(lines[i])
        i += 1
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


def render_xposed_readme(
    src_readme: str,
    *,
    version: str,
    version_code: str,
    source_repo: str,
    xposed_repo: str,
    branch: str = "main",
) -> str:
    text = src_readme.replace("\r\n", "\n")

    raw_base = f"https://raw.githubusercontent.com/{source_repo}/{branch}"
    blob_base = f"https://github.com/{source_repo}/blob/{branch}"

    # 1. Convert centered HTML <img> tags with relative docs/images paths to standard Markdown images.
    def replace_html_img(match: re.Match[str]) -> str:
        rel_path = match.group("src").lstrip("./")
        alt = match.group("alt")
        return f"![{alt}]({raw_base}/{rel_path})"

    text = re.sub(
        r'<p\s+align="center">\s*<img\s+src="(?P<src>docs/images/[^"]+)"\s+alt="(?P<alt>[^"]*)"[^>]*>\s*</p>',
        replace_html_img,
        text,
    )

    # Also rewrite any Markdown image pointing to relative docs/images/.
    text = re.sub(
        r"!\[(?P<alt>[^\]]*)\]\((?P<src>(?:\./)?docs/images/[^)]+)\)",
        lambda m: f"![{m.group('alt')}]({raw_base}/{m.group('src').lstrip('./')})",
        text,
    )

    # 2. Localize top title and inject concise Xposed-Modules-Repo quick links after the intro paragraph.
    text = re.sub(
        r"^#[ \t]+LocalAiNotif For HyperOS[ \t]*$",
        "# 端侧AI通知总结 For HyperOS（LocalAiNotif For HyperOS）",
        text,
        count=1,
        flags=re.MULTILINE,
    )

    quick_meta = (
        f"\n- 包名：`io.github.internetbugs.localainotif`\n"
        f"- 当前版本：**{version}**（versionCode `{version_code}`）\n"
        f"- [下载模块 APK](https://github.com/{xposed_repo}/releases)\n"
        f"- [源码、构建说明与问题反馈](https://github.com/{source_repo})\n"
    )
    intro_pattern = re.compile(r"^(\*\*LocalAiNotif For HyperOS\*\*[^\n]*\n)", re.MULTILINE)
    if not intro_pattern.search(text):
        raise ValueError("Expected intro paragraph starting with '**LocalAiNotif For HyperOS**' in README.md")
    text = intro_pattern.sub(lambda m: m.group(1) + quick_meta, text, count=1)

    # 3. Convert GitHub-only alert blocks to portable blockquotes.
    text = convert_github_alerts(text)

    # 4. Strip developer-only '## 从源码构建' section (Xposed-Modules-Repo distributes metadata & APKs only).
    build_section = re.compile(
        r"\n## 从源码构建\n.*?(?=\n---\n\n## 致谢\n)",
        re.DOTALL,
    )
    if build_section.search(text):
        text = build_section.sub("", text, count=1)
        text = text.replace("\n---\n\n---\n\n## 致谢\n", "\n---\n\n## 致谢\n")

    # 5. Point '第一步' download link primarily to Xposed-Modules-Repo Releases.
    text = text.replace(
        f"1. 前往 [Releases](https://github.com/{source_repo}/releases) 下载最新正式版安装包（不含模型权重，体积轻巧）：",
        f"1. 前往本仓库 [Releases](https://github.com/{xposed_repo}/releases)（或通过 LSPosed 管理器仓库页）下载最新正式版安装包（不含模型权重，体积轻巧；Debug 调试包与校验和见主仓库 [Releases](https://github.com/{source_repo}/releases)）：",
    )

    # 6. Rewrite any remaining repo-relative Markdown links (excluding LICENSE and anchors/URLs).
    def replace_rel_link(match: re.Match[str]) -> str:
        prefix = match.group("prefix")
        label = match.group("label")
        target = match.group("target").strip()
        if (
            target.startswith(("http://", "https://", "#", "mailto:"))
            or target == "LICENSE"
        ):
            return match.group(0)
        return f"{prefix}[{label}]({blob_base}/{target.lstrip('./')})"

    text = re.sub(
        r"(?P<prefix>(?<!!))\[(?P<label>[^\]]+)\]\((?P<target>[^)\s]+)\)",
        replace_rel_link,
        text,
    )

    # 7. Append explicit distribution-repo footer.
    footer = (
        f"\n> 本仓库仅用于模块介绍与 APK 分发，不存放源码。"
        f"完整源码、从源码构建说明与问题反馈请前往 [{source_repo}](https://github.com/{source_repo})。\n"
    )
    text = text.rstrip() + "\n" + footer

    # Sanity checks to guarantee clean rendering on both GitHub and modules.lsposed.org.
    if "## 从源码构建" in text:
        raise ValueError("Rendered README still contains '## 从源码构建'")
    if re.search(r"\]\((?:\./)?docs/images/", text) or 'src="docs/images/' in text:
        raise ValueError("Rendered README still contains relative docs/images/ reference")
    if re.search(r"^>\s*\[!(?:IMPORTANT|NOTE|TIP|WARNING|CAUTION)\]", text, re.MULTILINE):
        raise ValueError("Rendered README still contains unconverted GitHub alert syntax")
    if f"**{version}**（versionCode `{version_code}`）" not in text:
        raise ValueError("Rendered README is missing version metadata")

    return text


def render_xposed_release_notes(
    *,
    version: str,
    tag: str,
    source_repo: str,
    input_notes: str,
    main_release_body: str,
) -> str:
    body = input_notes.replace("\r\n", "\n").strip()
    if not body and main_release_body:
        cleaned = main_release_body.replace("\r\n", "\n")
        # Strip the main-repo package selector block that lists both Release and Debug APKs.
        cleaned = re.sub(
            r"^##\s+选择安装包\n.*?(?=\n##\s+|\Z)",
            "",
            cleaned,
            count=1,
            flags=re.DOTALL,
        ).strip()
        body = cleaned

    parts: list[str] = []
    if body:
        parts.append(body)
    else:
        parts.append(f"发布 `v{version}` 正式版。")

    parts.append(
        f"> 完整变更记录、Debug 调试包与 `SHA256SUMS.txt` 校验和请前往主仓库 "
        f"[Releases ({tag})](https://github.com/{source_repo}/releases/tag/{tag})。"
    )
    return "\n\n".join(parts) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-readme", required=True, type=pathlib.Path)
    parser.add_argument("--source-license", required=True, type=pathlib.Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-repo", required=True)
    parser.add_argument("--xposed-repo", required=True)
    parser.add_argument("--out-repo-dir", required=True, type=pathlib.Path)
    parser.add_argument("--out-notes-file", required=True, type=pathlib.Path)
    parser.add_argument("--input-notes-file", type=pathlib.Path)
    parser.add_argument("--main-release-body-file", type=pathlib.Path)
    args = parser.parse_args()

    out_repo: pathlib.Path = args.out_repo_dir
    out_repo.mkdir(parents=True, exist_ok=True)

    rendered_readme = render_xposed_readme(
        args.source_readme.read_text(encoding="utf-8"),
        version=args.version,
        version_code=args.version_code,
        source_repo=args.source_repo,
        xposed_repo=args.xposed_repo,
    )
    (out_repo / "README.md").write_bytes(rendered_readme.encode("utf-8"))

    license_bytes = args.source_license.read_bytes().replace(b"\r\n", b"\n")
    (out_repo / "LICENSE").write_bytes(license_bytes)

    (out_repo / "SOURCE_URL").write_bytes(f"https://github.com/{args.source_repo}\n".encode("utf-8"))

    summary_path = out_repo / "SUMMARY"
    if not summary_path.exists() or not summary_path.read_text(encoding="utf-8").strip():
        summary_path.write_bytes(DEFAULT_SUMMARY.encode("utf-8"))

    input_notes = (
        args.input_notes_file.read_text(encoding="utf-8")
        if args.input_notes_file and args.input_notes_file.exists()
        else ""
    )
    main_body = (
        args.main_release_body_file.read_text(encoding="utf-8")
        if args.main_release_body_file and args.main_release_body_file.exists()
        else ""
    )
    notes = render_xposed_release_notes(
        version=args.version,
        tag=args.tag,
        source_repo=args.source_repo,
        input_notes=input_notes,
        main_release_body=main_body,
    )
    args.out_notes_file.parent.mkdir(parents=True, exist_ok=True)
    args.out_notes_file.write_bytes(notes.encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
