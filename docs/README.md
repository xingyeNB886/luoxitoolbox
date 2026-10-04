# 洛茜工具箱

一个 Android 上的游戏文件管理工具，通过 Root 或 Shizuku 权限操作游戏私有目录。

[![Latest release](https://img.shields.io/github/v/release/xingyeNB886/luoxitoolbox?label=Release&logo=github)](https://github.com/xingyeNB886/luoxitoolbox/releases/latest)
[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-orange.svg?logo=gnu)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)
[![GitHub License](https://img.shields.io/github/license/xingyeNB886/luoxitoolbox?logo=gnu)](/LICENSE)

## 功能

- 图片多选与裁剪：按设备分辨率比例裁剪，裁剪框限制在图片内部，不超出边界。
- 批量制作文件：按记录的游戏文件名自动复制并命名，均分到每张图片，保留原始画质。
- 游戏文件替换：一键替换游戏加载图目录，支持提前备份，失败自动回滚。
- 双权限通道：同时支持 Root（KernelSU）和 Shizuku（ADB），自动检测可用权限。

## 兼容状态

运行环境：Android 11+，需要 Root 或 Shizuku 权限。

支持架构：`arm64-v8a` 和 `x86_64`。

## 使用方法

- [Release 下载](https://github.com/xingyeNB886/luoxitoolbox/releases)
- [开发指南](https://github.com/xingyeNB886/luoxitoolbox/blob/main/luoxi-dev-guide.md)

## 讨论

- QQ群: [群1](https://qm.qq.com/q/TEuQTWTu48) / [群2](https://qm.qq.com/q/9XnN0A6PbW)

## 安全性

有关报告安全漏洞的信息，请参阅 [SECURITY.md](/SECURITY.md)。

## 许可证

- 目录 `kernel` 下所有文件为 [GPL-2.0-only](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)。
- 除 `kernel` 目录的其他部分均为 [GPL-3.0-or-later](https://www.gnu.org/licenses/gpl-3.0.html)。

## 鸣谢

- [KernelSU](https://github.com/tiann/KernelSU)：本工具基于 KernelSU 管理器二开。
- [Magisk](https://github.com/topjohnwu/Magisk)：强大的 root 工具箱。
- [Shizuku](https://github.com/RikkaApps/Shizuku)：提供 ADB 权限通道。
- [genuine](https://github.com/brevent/genuine/)：apk v2 签名验证。
- [Diamorphine](https://github.com/m0nad/Diamorphine)：一些 rootkit 技巧。