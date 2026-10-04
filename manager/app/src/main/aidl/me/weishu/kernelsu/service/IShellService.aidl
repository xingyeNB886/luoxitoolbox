// Shizuku UserService 接口：在 Shizuku (adb/root) 环境中执行 shell 命令
package me.weishu.kernelsu.service;

interface IShellService {
    // 执行 shell 命令并返回合并后的 stdout+stderr
    String exec(String command);
}
