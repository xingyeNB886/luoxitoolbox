package me.weishu.kernelsu

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * @author weishu
 * @date 2022/12/8.
 */
object Natives {
    // minimal supported kernel version
    // 10915: allowlist breaking change, add app profile
    // 10931: app profile struct add 'version' field
    // 10946: add capabilities
    // 10977: change groups_count and groups to avoid overflow write
    // 11071: Fix the issue of failing to set a custom SELinux type.
    // 12143: breaking: new supercall impl
    const val MINIMAL_SUPPORTED_KERNEL = 22000

    const val KERNEL_SU_DOMAIN = "u:r:su:s0"

    const val ROOT_UID = 0
    const val ROOT_GID = 0

    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    private const val NOBODY_UID = 9999

    /**
     * native lib 是否成功加载。
     * 洛茜工具箱：不加载 libkernelsu.so —— 该库的 JNI_OnLoad 在非 KernelSU 环境下
     * 会触发 native SIGABRT，Java try-catch 无法拦截，导致直接闪退且无崩溃页。
     * 所有 native 方法已做 isLibLoaded 检查，返回安全默认值。
     */
    val isLibLoaded: Boolean = false

    private val version0: Int
        external get
    val version: Int
        get() = if (isLibLoaded) runCatching { version0 }.getOrDefault(-1) else -1

    private val allowList0: IntArray
        external get
    val allowList: IntArray
        get() = if (isLibLoaded) runCatching { allowList0 }.getOrDefault(IntArray(0)) else IntArray(0)

    private val isSafeMode0: Boolean
        external get
    val isSafeMode: Boolean
        get() = if (isLibLoaded) runCatching { isSafeMode0 }.getOrDefault(false) else false

    private val isLkmMode0: Boolean
        external get
    val isLkmMode: Boolean
        get() = if (isLibLoaded) runCatching { isLkmMode0 }.getOrDefault(false) else false

    private val isManager0: Boolean
        external get
    val isManager: Boolean
        get() = if (isLibLoaded) runCatching { isManager0 }.getOrDefault(false) else false

    private external fun uidShouldUmount0(uid: Int): Boolean
    fun uidShouldUmount(uid: Int): Boolean =
        if (isLibLoaded) runCatching { uidShouldUmount0(uid) }.getOrDefault(false) else false

    /**
     * Get the profile of the given package.
     * @param key usually the package name
     * @return return null if failed.
     */
    private external fun getAppProfile0(key: String?, uid: Int): Profile
    fun getAppProfile(key: String?, uid: Int): Profile = run {
        if (!isLibLoaded) return Profile(NON_ROOT_DEFAULT_PROFILE_KEY, NOBODY_UID, false, umountModules = true)
        runCatching { getAppProfile0(key, uid) }
            .getOrElse { Profile(key ?: NON_ROOT_DEFAULT_PROFILE_KEY, uid, false, umountModules = true) }
    }

    private external fun setAppProfile0(profile: Profile?): Boolean
    fun setAppProfile(profile: Profile?): Boolean =
        if (isLibLoaded) runCatching { setAppProfile0(profile) }.getOrDefault(false) else false

    /**
     * `su` compat mode can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    private external fun isSuEnabled0(): Boolean
    fun isSuEnabled(): Boolean =
        if (isLibLoaded) runCatching { isSuEnabled0() }.getOrDefault(false) else false

    private external fun setSuEnabled0(enabled: Boolean): Boolean
    fun setSuEnabled(enabled: Boolean): Boolean =
        if (isLibLoaded) runCatching { setSuEnabled0(enabled) }.getOrDefault(false) else false

    /**
     * Kernel module umount can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    private external fun isKernelUmountEnabled0(): Boolean
    fun isKernelUmountEnabled(): Boolean =
        if (isLibLoaded) runCatching { isKernelUmountEnabled0() }.getOrDefault(false) else false

    private external fun setKernelUmountEnabled0(enabled: Boolean): Boolean
    fun setKernelUmountEnabled(enabled: Boolean): Boolean =
        if (isLibLoaded) runCatching { setKernelUmountEnabled0(enabled) }.getOrDefault(false) else false

    /**
     * Get the user name for the uid.
     */
    private external fun getUserName0(uid: Int): String?
    fun getUserName(uid: Int): String? =
        if (isLibLoaded) runCatching { getUserName0(uid) }.getOrNull() else null

    fun setDefaultUmountModules(umountModules: Boolean): Boolean {
        Profile(
            NON_ROOT_DEFAULT_PROFILE_KEY,
            NOBODY_UID,
            false,
            umountModules = umountModules
        ).let {
            return setAppProfile(it)
        }
    }

    fun isDefaultUmountModules(): Boolean {
        getAppProfile(NON_ROOT_DEFAULT_PROFILE_KEY, NOBODY_UID).let {
            return it.umountModules
        }
    }

    fun requireNewKernel(): Boolean = false

    @Keep
    @Immutable
    @Parcelize
    @Serializable
    data class Profile(
        // and there is a default profile for root and non-root
        val name: String,
        // current uid for the package, this is convivent for kernel to check
        // if the package name doesn't match uid, then it should be invalidated.
        val currentUid: Int = 0,

        // if this is true, kernel will grant root permission to this package
        val allowSu: Boolean = false,

        // these are used for root profile
        val rootUseDefault: Boolean = true,
        val rootTemplate: String? = null,
        val uid: Int = ROOT_UID,
        val gid: Int = ROOT_GID,
        val groups: List<Int> = mutableListOf(),
        val capabilities: List<Int> = mutableListOf(),
        val context: String = KERNEL_SU_DOMAIN,
        val namespace: Int = Namespace.INHERITED.ordinal,

        val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true,
        var rules: String = "", // this field is save in ksud!!
    ) : Parcelable {
        enum class Namespace {
            INHERITED,
            GLOBAL,
            INDIVIDUAL,
        }

        constructor() : this("")
    }
}
