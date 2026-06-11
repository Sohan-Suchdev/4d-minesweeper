FROM nginx:1.27-alpine

# Restrict envsubst to BACKEND_* so nginx's own variables ($host, $remote_addr, …) stay literal.
ENV NGINX_ENVSUBST_FILTER="^BACKEND_"

# Files in /etc/nginx/templates/ are envsubst-processed into /etc/nginx/conf.d/ on container start.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

COPY index.html style.css app.js /usr/share/nginx/html/

EXPOSE 80
