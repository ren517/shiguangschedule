package com.xingheyuzhuan.shiguangschedule.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xingheyuzhuan.shiguangschedule.data.api.jwxt.JwxtAuthClient
import com.xingheyuzhuan.shiguangschedule.tool.SecureCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** 教务账号持久化所使用的 DataStore 限定名 */
const val JWXT_ACCOUNT_DATA_STORE = "JwxtAccount"

/**
 * 界面展示用的教务账号。
 *
 * @property studentId 学号
 * @property name 姓名
 * @property hasPassword 是否已保存可用于自动重登的密码
 * @property hasSession 是否已缓存教务会话 Cookie
 */
data class JwxtUser(
    val studentId: String,
    val name: String,
    val hasPassword: Boolean,
    val hasSession: Boolean
)

/**
 * 教务账号的存储结构（密码与会话均加密，绝不落明文）。
 */
@Serializable
private data class JwxtAccountRecord(
    val studentId: String = "",
    val name: String = "",
    val encryptedPassword: String = "",
    val passwordIv: String = "",
    val encryptedCookie: String = "",
    val cookieIv: String = ""
)

/**
 * 西安科技大学教务账号仓库（支持多账号）。
 *
 * 与 xust-grade-web 的 users/ + cookies/ 双目录等价：
 * 账号表整体加密后写入 DataStore，会话 Cookie 逐账号独立缓存，
 * 会话失效时可用已保存的密码静默重登。
 */
@Single
class JwxtUserRepository(
    @Named(JWXT_ACCOUNT_DATA_STORE) private val dataStore: DataStore<Preferences>
) {

    private val authClient = JwxtAuthClient()

    // SecureCrypto 依赖平台 Keystore，未纳入 Koin 依赖图，这里直接持有实例
    private val secureCrypto = SecureCrypto()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 教务账号相关的存储键
     */
    object AccountKeys {
        private const val PREFIX = "jwxt_"
        val USERS = stringPreferencesKey("${PREFIX}users")
        val CURRENT_STUDENT_ID = stringPreferencesKey("${PREFIX}current_student_id")
    }

    /** 观察全部已保存账号 */
    val usersFlow: Flow<List<JwxtUser>> = dataStore.data.map { preferences ->
        readRecords(preferences[AccountKeys.USERS]).map { record ->
            JwxtUser(
                studentId = record.studentId,
                name = record.name,
                hasPassword = record.encryptedPassword.isNotEmpty(),
                hasSession = record.encryptedCookie.isNotEmpty()
            )
        }
    }

    /** 观察当前选中的学号 */
    val currentStudentIdFlow: Flow<String> = dataStore.data.map { preferences ->
        resolveCurrentStudentId(preferences)
    }

    /** 观察当前选中的账号（未选择时为空） */
    val currentUserFlow: Flow<JwxtUser?> = dataStore.data.map { preferences ->
        val records = readRecords(preferences[AccountKeys.USERS])
        val currentId = resolveCurrentStudentId(preferences)
        records.firstOrNull { it.studentId == currentId }?.let { record ->
            JwxtUser(
                studentId = record.studentId,
                name = record.name,
                hasPassword = record.encryptedPassword.isNotEmpty(),
                hasSession = record.encryptedCookie.isNotEmpty()
            )
        }
    }

    suspend fun getUsers(): List<JwxtUser> = usersFlow.first()

    suspend fun getCurrentStudentId(): String = currentStudentIdFlow.first()

    /**
     * 添加教务账号：先真正登录一次教务系统验证凭据，通过后才落盘。
     *
     * @param name 姓名
     * @param studentId 学号
     * @param password 教务系统明文密码
     * @return 成功返回 Unit，失败返回携带原因的异常
     */
    suspend fun addUser(name: String, studentId: String, password: String): Result<Unit> {
        val xh = studentId.trim()
        if (xh.isEmpty()) return Result.failure(IllegalArgumentException("学号不能为空"))
        if (password.isBlank()) return Result.failure(IllegalArgumentException("教务密码不能为空"))

        val existing = readRecords().firstOrNull { it.studentId == xh }
        if (existing != null) {
            return Result.failure(IllegalArgumentException("学号 $xh 已存在，不能重复添加"))
        }

        // 与 xust-grade-web/main.py 的 verify_password() 一致：先真登录一次再落盘
        // 登录失败时透传真实原因（网络不可达 / 密码错误 / 取不到令牌），便于定位问题
        val loginResult = withContext(Dispatchers.IO) {
            authClient.login(xh, password)
        }
        val cookie = loginResult.getOrElse { error ->
            return Result.failure(
                IllegalStateException(error.message ?: "教务登录未通过验证，请检查账号密码与网络")
            )
        }

        val passwordCipher = secureCrypto.encrypt(password)
            ?: return Result.failure(IllegalStateException("密码加密失败，无法保存账号"))
        val cookieCipher = secureCrypto.encrypt(cookie)
            ?: return Result.failure(IllegalStateException("会话加密失败，无法保存账号"))

        val record = JwxtAccountRecord(
            studentId = xh,
            name = name.trim().ifEmpty { xh },
            encryptedPassword = passwordCipher.encryptedData,
            passwordIv = passwordCipher.iv,
            encryptedCookie = cookieCipher.encryptedData,
            cookieIv = cookieCipher.iv
        )

        dataStore.edit { preferences ->
            val records = readRecords(preferences[AccountKeys.USERS]).toMutableList()
            records.add(record)
            preferences[AccountKeys.USERS] = json.encodeToString(records)
            if (resolveCurrentStudentId(preferences).isEmpty()) {
                preferences[AccountKeys.CURRENT_STUDENT_ID] = xh
            }
        }

        return Result.success(Unit)
    }

    /**
     * 删除教务账号：同时清除其会话与选中态。
     */
    suspend fun deleteUser(studentId: String) {
        val xh = studentId.trim()

        dataStore.edit { preferences ->
            val records = readRecords(preferences[AccountKeys.USERS])
                .filterNot { it.studentId == xh }
            preferences[AccountKeys.USERS] = json.encodeToString(records)

            if (resolveCurrentStudentId(preferences) == xh) {
                preferences[AccountKeys.CURRENT_STUDENT_ID] = records.firstOrNull()?.studentId ?: ""
            }
        }
    }

    /**
     * 切换当前使用的教务账号。
     */
    suspend fun switchUser(studentId: String) {
        dataStore.edit { preferences ->
            preferences[AccountKeys.CURRENT_STUDENT_ID] = studentId.trim()
        }
    }

    /**
     * 读取指定账号解密后的密码。
     */
    suspend fun getPassword(studentId: String): String? {
        val record = findRecord(studentId) ?: return null
        if (record.encryptedPassword.isEmpty()) return null
        return secureCrypto.decrypt(record.encryptedPassword, record.passwordIv)
    }

    /**
     * 读取指定账号解密后的会话 Cookie。
     */
    suspend fun getSessionCookie(studentId: String): String? {
        val record = findRecord(studentId) ?: return null
        if (record.encryptedCookie.isEmpty()) return null
        return secureCrypto.decrypt(record.encryptedCookie, record.cookieIv)
    }

    /**
     * 清除指定账号的会话 Cookie（保留密码以便静默重登）。
     */
    suspend fun clearSessionCookie(studentId: String) {
        dataStore.edit { preferences ->
            val records = readRecords(preferences[AccountKeys.USERS]).toMutableList()
            val index = records.indexOfFirst { it.studentId == studentId }
            if (index >= 0) {
                records[index] = records[index].copy(encryptedCookie = "", cookieIv = "")
                preferences[AccountKeys.USERS] = json.encodeToString(records)
            }
        }
    }

    /**
     * 取得可用的会话 Cookie：优先复用已缓存的会话，失效时用已保存密码重登。
     *
     * @return 会话 Cookie；账号不存在或登录失败时返回 null
     */
    suspend fun ensureSession(studentId: String = ""): String? {
        val xh = studentId.trim().ifEmpty { getCurrentStudentId() }
        if (xh.isEmpty()) return null

        val saved = getSessionCookie(xh)
        if (!saved.isNullOrEmpty()) return saved

        return relogin(xh)
    }

    /**
     * 用已保存的账号密码重新走一遍 CAS 登录，成功后刷新缓存的会话 Cookie。
     */
    suspend fun relogin(studentId: String): String? {
        val password = getPassword(studentId) ?: return null
        if (password.isEmpty()) return null

        val cookie = withContext(Dispatchers.IO) {
            authClient.login(studentId, password).getOrNull()
        } ?: return null

        val cipher = secureCrypto.encrypt(cookie) ?: return null

        dataStore.edit { preferences ->
            val records = readRecords(preferences[AccountKeys.USERS]).toMutableList()
            val index = records.indexOfFirst { it.studentId == studentId }
            if (index >= 0) {
                records[index] = records[index].copy(
                    encryptedCookie = cipher.encryptedData,
                    cookieIv = cipher.iv
                )
                preferences[AccountKeys.USERS] = json.encodeToString(records)
            }
        }

        return cookie
    }

    /**
     * 会话失效后刷新一次：清除旧 Cookie 并重新登录。
     */
    suspend fun refreshSession(studentId: String): String? {
        clearSessionCookie(studentId)
        return relogin(studentId)
    }

    private suspend fun findRecord(studentId: String): JwxtAccountRecord? {
        val preferences = dataStore.data.first()
        return readRecords(preferences[AccountKeys.USERS]).firstOrNull { it.studentId == studentId }
    }

    private suspend fun readRecords(): List<JwxtAccountRecord> {
        val preferences = dataStore.data.first()
        return readRecords(preferences[AccountKeys.USERS])
    }

    private fun readRecords(raw: String?): List<JwxtAccountRecord> {
        if (raw.isNullOrBlank()) return emptyList()

        return try {
            json.decodeFromString<List<JwxtAccountRecord>>(raw)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 当前学号：优先读显式选中项，未设置时回退到第一个账号。
     */
    private fun resolveCurrentStudentId(preferences: Preferences): String {
        val explicit = preferences[AccountKeys.CURRENT_STUDENT_ID].orEmpty()
        if (explicit.isNotEmpty()) return explicit

        val records = readRecords(preferences[AccountKeys.USERS])
        val firstId = records.firstOrNull()?.studentId.orEmpty()
        return firstId
    }
}
