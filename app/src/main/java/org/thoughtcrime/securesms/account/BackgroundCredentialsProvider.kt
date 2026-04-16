package org.thoughtcrime.securesms.account

import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.whispersystems.signalservice.api.util.CredentialsProvider

/**
 * A [CredentialsProvider] backed by cached credentials from [AccountRegistry.AccountEntry],
 * independent of [SignalStore]. This allows a background WebSocket to authenticate
 * without loading the full singleton stack for the account.
 */
class BackgroundCredentialsProvider(
  private val aci: ACI,
  private val pni: PNI?,
  private val e164: String?,
  private val deviceId: Int,
  private val password: String
) : CredentialsProvider {

  override fun getAci(): ACI = aci
  override fun getPni(): PNI? = pni
  override fun getE164(): String? = e164
  override fun getDeviceId(): Int = deviceId
  override fun getPassword(): String = password

  companion object {
    /**
     * Creates a [BackgroundCredentialsProvider] from a cached [AccountRegistry.AccountEntry].
     * Returns null if the entry doesn't have sufficient credentials (ACI + password).
     */
    @JvmStatic
    fun fromAccountEntry(entry: AccountRegistry.AccountEntry): BackgroundCredentialsProvider? {
      val aci = ACI.parseOrNull(entry.aci) ?: return null
      val password = entry.servicePassword ?: return null

      return BackgroundCredentialsProvider(
        aci = aci,
        pni = entry.pni?.let { PNI.parseOrNull(it) },
        e164 = entry.e164,
        deviceId = entry.deviceId,
        password = password
      )
    }
  }
}
