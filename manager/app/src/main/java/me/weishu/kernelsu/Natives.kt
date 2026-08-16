package me.weishu.kernelsu

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.weishu.kernelsu.Natives.Profile.RootProfileFlag

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
    // 32310: new get_allow_list ioctl
    // 32336: new set_sepolicy ioctl
    // 32377: add set_init_pgrp ioctl
    // 32513: add uapi version
    const val MINIMAL_SUPPORTED_KERNEL = 32513

    const val KERNEL_SU_DOMAIN = "u:r:ksu:s0"

    const val ROOT_UID = 0
    const val ROOT_GID = 0

    // Natives 对象里被 getAppProfile fallback 用到的常量，必须放在属性定义最前。
    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    private const val NOBODY_UID = 9999

    /** native lib 是否成功加载；如果没加载成功，所有访问都返回默认值 */
    val isLibLoaded: Boolean = runCatching {
        System.loadLibrary("kernelsu")
        true
    }.getOrElse { false }

    private val version0: Int
        external get
    val version: Int
        get() = if (isLibLoaded) runCatching { version0 }.getOrDefault(-1) else -1

    private val isSafeMode0: Boolean
        external get
    val isSafeMode: Boolean
        get() = if (isLibLoaded) runCatching { isSafeMode0 }.getOrDefault(false) else false

    private val isLkmMode0: Boolean
        external get
    val isLkmMode: Boolean
        get() = if (isLibLoaded) runCatching { isLkmMode0 }.getOrDefault(false) else false

    private val isLateLoadMode0: Boolean
        external get
    val isLateLoadMode: Boolean
        get() = if (isLibLoaded) runCatching { isLateLoadMode0 }.getOrDefault(false) else false

    private val isManager0: Boolean
        external get
    val isManager: Boolean
        get() = if (isLibLoaded) runCatching { isManager0 }.getOrDefault(false) else false

    private val isPrBuild0: Boolean
        external get
    val isPrBuild: Boolean
        get() = if (isLibLoaded) runCatching { isPrBuild0 }.getOrDefault(false) else false

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
     * SELinux hide can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    private external fun isSelinuxHideEnabled0(): Boolean
    fun isSelinuxHideEnabled(): Boolean =
        if (isLibLoaded) runCatching { isSelinuxHideEnabled0() }.getOrDefault(false) else false

    private external fun setSelinuxHideEnabled0(enabled: Boolean): Int
    fun setSelinuxHideEnabled(enabled: Boolean): Int =
        if (isLibLoaded) runCatching { setSelinuxHideEnabled0(enabled) }.getOrDefault(-1) else -1

    /**
     * Get the user name for the uid.
     */
    private external fun getUserName0(uid: Int): String?
    fun getUserName(uid: Int): String? =
        if (isLibLoaded) runCatching { getUserName0(uid) }.getOrNull() else null

    private external fun getSuperuserCount0(): Int
    fun getSuperuserCount(): Int =
        if (isLibLoaded) runCatching { getSuperuserCount0() }.getOrDefault(0) else 0

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

    private val kernelUAPIVersion0: Int
        external get
    val kernelUAPIVersion: Int
        get() = if (isLibLoaded) runCatching { kernelUAPIVersion0 }.getOrDefault(-1) else -1

    private val managerUAPIVersion0: Int
        external get
    val managerUAPIVersion: Int
        get() = if (isLibLoaded) runCatching { managerUAPIVersion0 }.getOrDefault(-1) else -1

    fun checkUAPIMismatch(): Boolean = runCatching {
        kernelUAPIVersion != -1 && managerUAPIVersion != -1 && kernelUAPIVersion != managerUAPIVersion
    }.getOrDefault(false)

    fun requireNewKernel(): Boolean = runCatching {
        (version != -1 && version < MINIMAL_SUPPORTED_KERNEL) || checkUAPIMismatch()
    }.getOrDefault(false)

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

        val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
    ) : Parcelable {
        @Keep
        enum class RootProfileFlag(val display: String, val desc: Int) {
            NO_NEW_PRIVS(
                "NO_NEW_PRIVS",
                R.string.profile_flags_desc_no_new_privs
            )
        }

        enum class Namespace {
            INHERITED,
            GLOBAL,
            INDIVIDUAL,
        }

        constructor() : this("")
    }

    const val FLAG_KSU_NO_NEW_PRIVS = 1L
}

fun List<RootProfileFlag>.toRawFlags(): Long =
    fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }

fun List<RootProfileFlag>.toOrdinalList(): List<Int> =
    map { it.ordinal }

fun Long.toRootProfileFlags(): List<RootProfileFlag> =
    RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }.toList()
