# 1단계: 의존성 설치
FROM node:22-slim AS deps
WORKDIR /build
RUN corepack enable pnpm
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# 2단계: 빌드 (standalone 출력)
FROM node:22-slim AS build
WORKDIR /build
RUN corepack enable pnpm
COPY --from=deps /build/node_modules ./node_modules
COPY . .
# 브라우저 번들에 들어가는 값이라 빌드 시점에 필요하다
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN pnpm build

# 3단계: 실행
FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production PORT=3000
RUN useradd --system --create-home --uid 10001 nextjs
USER nextjs

COPY --from=build --chown=nextjs:nextjs /build/.next/standalone ./
COPY --from=build --chown=nextjs:nextjs /build/.next/static ./.next/static
COPY --from=build --chown=nextjs:nextjs /build/public ./public

EXPOSE 3000
CMD ["node", "server.js"]
