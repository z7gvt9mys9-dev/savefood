FROM node:20-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm install --legacy-peer-deps
COPY . .

ARG VITE_API_URL=""
ARG VITE_YANDEX_MAPS_API_KEY=""
ARG VITE_YANDEX_SUGGEST_API_KEY=""
ENV VITE_API_URL=$VITE_API_URL
ENV VITE_YANDEX_MAPS_API_KEY=$VITE_YANDEX_MAPS_API_KEY
ENV VITE_YANDEX_SUGGEST_API_KEY=$VITE_YANDEX_SUGGEST_API_KEY

RUN npm run build

FROM nginx:alpine
COPY --from=build /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
