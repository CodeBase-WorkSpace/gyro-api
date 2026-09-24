package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.verification.VerificationPurpose
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class VerificationEmailTemplate {
    fun render(code: String, expiresIn: Duration, purpose: VerificationPurpose): String {
        val title = when (purpose) {
            VerificationPurpose.SIGNUP -> "تایید ایمیل جیرو"
            VerificationPurpose.LOGIN -> "کد ورود به جیرو"
            VerificationPurpose.PASSWORD_RESET -> "بازیابی رمز عبور جیرو"
            VerificationPurpose.CHANGE_IDENTIFIER -> "تایید ایمیل جدید جیرو"
        }
        val lead = when (purpose) {
            VerificationPurpose.SIGNUP -> "برای تکمیل ساخت حساب، این کد را در صفحه تایید جیرو وارد کنید."
            VerificationPurpose.LOGIN -> "برای ورود امن به حساب، این کد یک‌بارمصرف را در جیرو وارد کنید."
            VerificationPurpose.PASSWORD_RESET -> "برای ثبت رمز عبور جدید، این کد بازیابی را در جیرو وارد کنید."
            VerificationPurpose.CHANGE_IDENTIFIER -> "برای تایید ایمیل جدید حساب، این کد را در جیرو وارد کنید."
        }
        val expiration = expiresIn.toFriendlyExpiration()

        return """
            <!doctype html>
            <html lang="fa" dir="rtl">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>$title</title>
              </head>
              <body style="margin:0;background:#020807;color:#e8f7f4;font-family:Vazirmatn,Tahoma,Arial,sans-serif;direction:rtl;">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" dir="rtl" style="background:#020807;padding:32px 16px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="100%" cellspacing="0" cellpadding="0" dir="rtl" style="max-width:560px;background:#07110f;border:1px solid #1d302c;border-radius:20px;overflow:hidden;box-shadow:0 18px 60px rgba(0,0,0,0.32);">
                        <tr>
                          <td style="background:#0a1815;color:#f4fffc;padding:24px 28px;border-bottom:1px solid #1d302c;text-align:right;">
                            <div style="font-size:24px;font-weight:800;letter-spacing:0;">جیرو</div>
                            <div style="font-size:14px;line-height:24px;color:#93b8b0;margin-top:4px;">حساب کاربری و تغذیه، با تایید امن.</div>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:30px 28px 28px;text-align:right;">
                            <div style="display:inline-block;background:#0f2420;border:1px solid #1f3c36;border-radius:999px;color:#55e0d2;font-size:12px;font-weight:700;line-height:18px;margin:0 0 16px;padding:6px 12px;">کد تایید</div>
                            <h1 style="font-size:23px;line-height:34px;margin:0 0 12px;color:#f4fffc;font-weight:800;">$title</h1>
                            <p style="font-size:15px;line-height:28px;margin:0 0 20px;color:#bad2cc;">$lead</p>
                            <div dir="ltr" style="font-size:34px;line-height:42px;font-weight:800;letter-spacing:8px;color:#07110f;background:#55e0d2;border:1px solid #87f3e9;border-radius:16px;padding:18px 20px;text-align:center;">$code</div>
                            <p style="font-size:14px;line-height:26px;margin:22px 0 0;color:#93b8b0;">این کد تا $expiration معتبر است. اگر شما این درخواست را ثبت نکرده‌اید، این ایمیل را نادیده بگیرید.</p>
                            <p style="font-size:13px;line-height:24px;margin:14px 0 0;color:#759891;">اگر کد کار نکرد، از داخل جیرو کد جدید بگیرید و همیشه آخرین کد ارسال‌شده را وارد کنید.</p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
              </body>
            </html>
        """.trimIndent()
    }

    private fun Duration.toFriendlyExpiration(): String {
        val minutes = toMinutes()
        if (minutes >= 1) {
            return "${minutes.toPersianDigits()} دقیقه"
        }

        val seconds = seconds.coerceAtLeast(1)
        return "${seconds.toPersianDigits()} ثانیه"
    }

    private fun Long.toPersianDigits(): String {
        return toString().map { digit ->
            when (digit) {
                '0' -> '۰'
                '1' -> '۱'
                '2' -> '۲'
                '3' -> '۳'
                '4' -> '۴'
                '5' -> '۵'
                '6' -> '۶'
                '7' -> '۷'
                '8' -> '۸'
                '9' -> '۹'
                else -> digit
            }
        }.joinToString("")
    }
}
