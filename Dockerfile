FROM centos:7
LABEL Author="Michal Karm Babacek <karm@redhat.com>"
ENV JAVA_VERSION 11
RUN yum install java-${JAVA_VERSION}-openjdk-headless apr openssl -y && yum clean all && \
    rm -rf /var/cache/yum /tmp/* && useradd -s /sbin/nologin tomcat && mkdir /opt/tomcat && chown tomcat /opt/tomcat
EXPOSE 8443/tcp
USER tomcat
WORKDIR /opt/tomcat
COPY --chown=tomcat:tomcat target/nativetlsdemo.jar /opt/tomcat
COPY --chown=tomcat:tomcat src/main/resources/libtcnative-1.so.0.2.21 /opt/tomcat/
COPY --chown=tomcat:tomcat src/main/resources/libapr-1.so.0.6.2 /opt/tomcat/
COPY --chown=tomcat:tomcat start.sh /opt/tomcat
CMD ["/opt/tomcat/start.sh"]
