FROM ubuntu/nginx:1.18-20.04_beta

USER root

RUN apt update && apt install -y gnupg2 ca-certificates && apt clean

RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 52FD40243E584A21

RUN echo deb https://repo.zextras.io/release/ubuntu jammy main > /etc/apt/sources.list.d/zextras.list

RUN apt update && \
    apt install -y \
    carbonio-webui \
    carbonio-shell-ui \
    carbonio-files-ui \
    carbonio-login-ui && \
    apt clean

COPY ./composed-ui/nginx-unified.conf /etc/nginx/conf.d/nginx-unified.conf
COPY ./composed-ui/nginx.carbonio.admin.conf /etc/nginx/conf.d/nginx.carbonio.admin.conf