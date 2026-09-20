FROM nginx:1.27-alpine
RUN apk add --no-cache openssl \
 && mkdir -p /etc/nginx/tls \
 && openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
      -subj /CN=localhost -keyout /etc/nginx/tls/local.key -out /etc/nginx/tls/local.crt
COPY nginx.conf /etc/nginx/nginx.conf
