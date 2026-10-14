package com.layerbit.sheaf.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/**
 * A digital signature from a certificate the person already has: a .pfx or .p12 from their
 * bank, their employer or a government signing service.
 *
 * The signature is a PKCS#7 detached CMS over the file's bytes, the kind Acrobat and every
 * reader's signature panel checks, written by an incremental save so the signed bytes are
 * exactly what the signature covers. The certificate and its password live in memory for
 * the length of the run and nowhere else.
 */
internal object Signing {

    class Credentials(val key: PrivateKey, val chain: List<X509Certificate>) {
        val certificate: X509Certificate get() = chain.first()
    }

    /** Opens the keystore and takes the first entry that has a private key. */
    fun load(file: File, password: String): Credentials {
        val store = try {
            KeyStore.getInstance("PKCS12").also { ks -> file.inputStream().use { ks.load(it, password.toCharArray()) } }
        } catch (e: Exception) {
            val wrongPassword = e.message?.contains("password", true) == true || e.message?.contains("mac", true) == true ||
                e.cause?.message?.contains("password", true) == true
            throw PdfException.Refused(
                if (wrongPassword) "That is not the certificate's password." else "This is not a certificate file Sheaf can read: it wants a .pfx or .p12.",
                e
            )
        }
        val alias = store.aliases().asSequence().firstOrNull { store.isKeyEntry(it) }
            ?: throw PdfException.Refused("This certificate file has no private key in it, so it cannot sign anything.")
        val key = store.getKey(alias, password.toCharArray()) as? PrivateKey
            ?: throw PdfException.Refused("This certificate file has no private key in it, so it cannot sign anything.")
        val chain = store.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate }.orEmpty()
        if (chain.isEmpty()) throw PdfException.Refused("This certificate file has no certificate in it.")
        return Credentials(key, chain)
    }

    /** "CN=Asha Menon, O=..." becomes "Asha Menon". */
    fun commonName(certificate: X509Certificate, issuer: Boolean = false): String {
        val name = (if (issuer) certificate.issuerX500Principal else certificate.subjectX500Principal).name
        return Regex("(?:^|,)\\s*CN=([^,]+)").find(name)?.groupValues?.get(1)?.trim()?.trim('"')
            ?: name.substringBefore(',').substringAfter('=').trim()
    }

    /**
     * Signs [doc], which must have been loaded from its file, writing the signed copy to
     * [output] incrementally.
     */
    fun sign(doc: PDDocument, credentials: Credentials, spec: SignSpec, output: OutputStream) {
        val now = Date()
        if (credentials.certificate.notAfter.before(now)) {
            val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(credentials.certificate.notAfter)
            throw PdfException.Refused("This certificate ran out on $day. A reader would mark the signature as not valid, so it was not used. Renew it with whoever issued it.")
        }
        val signature = PDSignature().apply {
            setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
            name = commonName(credentials.certificate)
            if (spec.reason.isNotBlank()) reason = spec.reason
            if (spec.location.isNotBlank()) location = spec.location
            signDate = Calendar.getInstance()
        }
        val options = SignatureOptions().apply { preferredSignatureSize = SIGNATURE_RESERVE }
        val signer = SignatureInterface { content -> cms(content, credentials) }
        doc.addSignature(signature, signer, options)
        doc.saveIncremental(output)
    }

    private fun cms(content: InputStream, credentials: Credentials): ByteArray {
        val algorithm = when (credentials.key.algorithm.uppercase()) {
            "EC", "ECDSA" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            else -> "SHA256withRSA"
        }
        val generator = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder(algorithm).build(credentials.key)
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .build(contentSigner, credentials.certificate)
        )
        generator.addCertificates(JcaCertStore(credentials.chain))
        val bytes = content.readBytes()
        val data = object : CMSTypedData {
            override fun getContentType(): ASN1ObjectIdentifier = CMSObjectIdentifiers.data
            override fun getContent(): Any = bytes
            override fun write(out: OutputStream) = out.write(bytes)
        }
        return generator.generate(data, false).encoded
    }

    /** Room left in the file for the signature; a chain of three certificates fits with space over. */
    private const val SIGNATURE_RESERVE = 16 * 1024
}

/** What the signature says about itself. */
data class SignSpec(
    val reason: String = "",
    val location: String = "",
    /** Show a box at the foot of a page saying who signed, or sign invisibly. */
    val visible: Boolean = true,
    /** The box goes on the first page rather than the last. */
    val onFirstPage: Boolean = false
)

/** Who signed, for the result. */
data class SignReport(val signer: String, val issuer: String)
