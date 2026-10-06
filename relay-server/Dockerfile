# Live VIP Smart Relay Server
FROM node:20-alpine

# ffmpeg does the codec-copy fan-out (no re-encoding → tiny CPU per destination)
RUN apk add --no-cache ffmpeg

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY server.js ./

EXPOSE 1935 8080

# RELAY_TOKEN is required — set it via env/compose, use a strong random value.
ENV HTTP_PORT=8080 RTMP_PORT=1935
CMD ["node", "server.js"]
