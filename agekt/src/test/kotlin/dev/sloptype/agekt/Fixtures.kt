package dev.sloptype.agekt

import java.util.Base64

/** Throwaway test key pair and a ciphertext, both produced by the age CLI (v1.3.2). */
object Fixtures {
    const val SECRET_KEY = "AGE-SECRET-KEY-1LCLYG4D3L03VCUCYZY483ZZSDCR9JHL7LK6PYTCDZXHNARNHF0YSHM30YA"
    const val PUBLIC_KEY = "age1hlnhxyrk2n66pqxy5ztj885wwxcrx0hsjgxtrxtxh92rse7zrqesmtdwk6"

    /** `printf 'hello agekt' | age -r $PUBLIC_KEY | base64` */
    val CLI_CIPHERTEXT: ByteArray = Base64.getDecoder().decode(
        "YWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOSBuQjVya0RCLzRwa3NVZWlGMzRURjM3S2Iwd212Q2VOdWdiYnU4SzZPbG1nCkdCaHVSaGFBakc1" +
            "czRHblVhWnlNM3ZPYlFKVGp6OS9TTzdVSjZOMlZTUE0KLS0tIEJNV1dycnZFRm1GSmlKWnk5MEExSmtpOURwRFN4ZmxydmVhelhoQTh4RzQKFPXl/ACd" +
            "aQJCIKtKb4Wg/i/4XU90sAABAKTv2hHMzVRvTNTjdHlbzHsLZg=="
    )
    const val CLI_PLAINTEXT = "hello agekt"
}
