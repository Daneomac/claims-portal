# syntax=docker/dockerfile:1

# ---- Stage 1: build the static site -----------------------------------------
FROM node:24-alpine AS build
WORKDIR /build

# Dependencies first so this layer is reused until package*.json change.
COPY package.json package-lock.json ./
RUN npm ci

# `npm run build` = type-check (tsc -b) + vite build -> /build/dist
# (tests and lint run in CI, not in the image build)
COPY . .
RUN npm run build

# ---- Stage 2: serve it with an unprivileged nginx ---------------------------
# nginx-unprivileged runs as the non-root "nginx" user (uid 101) and listens
# on 8080, since a non-root process can't bind ports below 1024.
FROM nginxinc/nginx-unprivileged:1.31-alpine

# Replace the stock server block with ours (see nginx.conf for the reasoning).
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 8080

