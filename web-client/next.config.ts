import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Docker 이미지를 작게 만들기 위해 실행에 필요한 파일만 모아 출력한다
  output: "standalone",
};

export default nextConfig;
