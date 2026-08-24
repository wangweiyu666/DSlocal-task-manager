import { ApiError } from "./http";
import type { Env } from "./types";
import { reserveEmail, type EmailPurpose } from "./usage";

export async function sendCode(env: Env, to: string, code: string, idempotencyKey: string, purpose: EmailPurpose = "SIGN_IN"): Promise<void> {
  await reserveEmail(env, purpose);
  if (env.ENVIRONMENT === "local") {
    await env.DB.prepare("INSERT INTO dev_mailbox(id, recipient, kind, secret, created_at) VALUES (?, ?, 'CODE', ?, ?)")
      .bind(idempotencyKey, to, code, new Date().toISOString()).run();
    return;
  }
  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.RESEND_API_KEY}`,
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify({
      from: env.RESEND_FROM,
      to: [to],
      subject: "DStationery 登录验证码",
      text: `你的验证码是 ${code}，10 分钟内有效。不要把验证码转发给任何人。`,
    }),
  });
  if (!response.ok) throw new ApiError(503, "MAIL_DELIVERY_FAILED", "验证码暂时无法投递，请稍后重试", true, 60);
}

export async function sendInvitation(env: Env, to: string, idempotencyKey: string): Promise<void> {
  await reserveEmail(env, "INVITATION");
  if (env.ENVIRONMENT === "local") {
    await env.DB.prepare("INSERT INTO dev_mailbox(id, recipient, kind, secret, created_at) VALUES (?, ?, 'INVITATION', ?, ?)")
      .bind(idempotencyKey, to, "", new Date().toISOString()).run();
    return;
  }
  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.RESEND_API_KEY}`,
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify({
      from: env.RESEND_FROM,
      to: [to],
      subject: "你收到了 DStationery 空间邀请",
      text: "请在 72 小时内打开 DStationery 联网 Android 版，并使用收到此邮件的邮箱账号登录。登录后即可在应用内查看并领取邀请；无需点击邀请链接。",
    }),
  });
  if (!response.ok) throw new ApiError(503, "MAIL_DELIVERY_FAILED", "邀请暂时无法投递，请稍后重试", true, 60);
}
