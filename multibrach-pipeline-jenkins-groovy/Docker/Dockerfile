FROM alpine:3.7

RUN apk update

ENV KUBE_LATEST_VERSION="v1.18.2"

RUN apk add --update ca-certificates \
 && apk add --update -t deps curl \
 && apk add git tar bash \
 && curl -L https://storage.googleapis.com/kubernetes-release/release/${KUBE_LATEST_VERSION}/bin/linux/amd64/kubectl -o /usr/local/bin/kubectl \
 && chmod +x /usr/local/bin/kubectl \
 && wget https://get.helm.sh/helm-v2.14.1-linux-amd64.tar.gz \
 && tar -xvf helm-v2.14.1-linux-amd64.tar.gz \
 && cd linux-amd64 \
 && mv helm /usr/local/bin/ \
 && chmod +x /usr/local/bin/helm \
 && helm init --client-only \
 && helm plugin install https://github.com/chartmuseum/helm-push \
 && helm plugin install https://github.com/rimusz/helm-tiller \
 && mkdir -p ~/.kube \
 && apk del --purge deps \
 && rm /var/cache/apk/* 

WORKDIR /root

RUN helm repo add example-qa https://exmaple.example.com/chartrepo/example --username example --password 12344567 \
 && helm repo update

RUN echo $'apiVersion: v1\n\
clusters:\n\
- cluster:\n\
    insecure-skip-tls-verify: true\n\
    server: https://<Cluster IP>:6443\n\
  name: example-qa\n\
contexts:\n\
- context:\n\
    cluster: example-qa\n\
    user: jenkins\n\
  name: example-qa\n\
- name: jenkins\n\
  user:\n\
    token: <add token for jenkins user here>' >> ~/.kube/config

CMD ["bash"]
