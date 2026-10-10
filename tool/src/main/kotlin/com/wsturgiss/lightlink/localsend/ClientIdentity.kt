package com.wsturgiss.lightlink.localsend

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/**
 * This phone's LocalSend identity: a client certificate.
 *
 * LocalSend runs mutual TLS — its server asks for a client certificate and
 * refuses the request without one ("certificate required"), because in HTTPS
 * mode the sender's fingerprint *is* the hash of that certificate. The protocol
 * README does not say so.
 *
 * The same certificate is the phone's server certificate when it receives
 * ([LocalSendServer]). It has to be: the computer pins the fingerprint the phone
 * registered with, which LocalSend checks against this client certificate.
 *
 * The key lives in the Android Keystore, which also issues the self-signed
 * certificate, so no key material ever sits in app storage.
 */
object ClientIdentity {
    private const val ALIAS = "localsend-client"

    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized
    fun certificate(): X509Certificate {
        if (!keyStore.containsAlias(ALIAS)) generate()
        return keyStore.getCertificate(ALIAS) as X509Certificate
    }

    fun fingerprint(): String = LocalSend.fingerprintOf(certificate().encoded)

    fun keyManager(): X509KeyManager {
        val chain = arrayOf(certificate())
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        return object : X509KeyManager {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getCertificateChain(alias: String?) = chain
            override fun getPrivateKey(alias: String?) = key
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            // The TLS stack asks per key type ("RSA", "EC"); only the EC one is ours.
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (isEc(keyType)) arrayOf(ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                ALIAS.takeIf { isEc(keyType) }
        }
    }

    private fun isEc(keyType: String?) = keyType == null || keyType.startsWith("EC")

    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
                    .setCertificateSubject(X500Principal("CN=Light Phone"))
                    .build(),
            )
        }.generateKeyPair()
    }
}
