/** 인증 API 계약 타입 (SPEC §2.1-6 ~ §2.1-10). */

export interface SignupRequest {
  email: string;
  password: string;
  name: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/** §2.1-7, §2.1-8 응답. Refresh Token은 httpOnly 쿠키라 본문에 없다. */
export interface TokenResponse {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
}

/** §2.1-6 회원가입 응답, §2.1-10 내 정보 조회 응답 */
export interface UserResponse {
  userId: number;
  email: string;
  name: string;
}
