# docker build -t querytimeout_query-timeout .

FROM jdk

WORKDIR /root

ADD target/universal/stage /root

EXPOSE 9000

CMD ["/root/bin/query-timeout"]
