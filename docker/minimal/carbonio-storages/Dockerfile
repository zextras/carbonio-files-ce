FROM docker.io/library/node:18-alpine

WORKDIR /app

COPY docker/minimal/carbonio-storages/package*.json ./

RUN npm install --production

COPY docker/minimal/carbonio-storages/server.js ./

RUN mkdir -p /storage

EXPOSE 10000

CMD ["node", "server.js"]