FROM node:18-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm install --legacy-peer-deps
COPY . .

ARG REACT_APP_API_URL=""
ARG REACT_APP_YANDEX_MAPS_API_KEY=""
ENV REACT_APP_API_URL=$REACT_APP_API_URL
ENV REACT_APP_YANDEX_MAPS_API_KEY=$REACT_APP_YANDEX_MAPS_API_KEY

RUN npm run build

FROM nginx:alpine
COPY --from=build /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
