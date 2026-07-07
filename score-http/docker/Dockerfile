# Spring Boot 4 requires a Servlet 6.1 container (#1750). Tomcat 10.1 is Servlet 6.0;
# Tomcat 11.0.x is Servlet 6.1 (Jakarta EE 11) and matches the embedded tomcat-embed 11.0.x
# pulled by the Boot 4.1 BOM.
FROM tomcat:11.0.23-jdk21-temurin

LABEL maintainer="Hakju Oh <hakju.oh@nist.gov>"

ARG MARIADB_CLIENT_VERSION

ENV DB_HOST=localhost \
    DB_PORT=3306 \
    DB_DATABASE=oagi \
    DB_USERNAME=oagi \
    REDIS_HOST=localhost \
    REDIS_PORT=6379

RUN apt-get -y update
RUN apt-get -y upgrade

COPY mariadb-java-client-${MARIADB_CLIENT_VERSION}.jar /usr/local/tomcat/lib/mariadb-java-client-${MARIADB_CLIENT_VERSION}.jar

RUN rm -rf /usr/local/tomcat/webapps/* && \
    chgrp -R 0 /usr/local/tomcat/ && \
    chmod -R g=u /usr/local/tomcat/

USER 1001
# NOTE (#1750): docker/web.xml is the stock Tomcat 10.1 conf/web.xml. The default/jsp servlet
# and filter defaults differ on Tomcat 11 — regenerate this from the tomcat:11.0.x image (or drop
# this COPY to use the container default) and validate on the modeldev instance before rollout.
COPY web.xml /usr/local/tomcat/conf/web.xml
COPY score-http.war /usr/local/tomcat/webapps/ROOT.war

CMD ["catalina.sh", "run"]
