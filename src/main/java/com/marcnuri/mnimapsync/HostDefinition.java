/*
 * Copyright 2013 Marc Nuri San Felix
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.marcnuri.mnimapsync;

import java.io.Serializable;
import java.util.Objects;

/**
 * Defines the connection settings for an IMAP server account
 *
 * @author Marc Nuri <marc@marcnuri.com>
 */
public class HostDefinition implements Serializable {

    private static final long serialVersionUID = -3209503227673718986L;

    private String host;
    private int port;
    private String user;
    private String password;
    private boolean ssl;
    private int retries = 3;
    private int connectTimeout = 30000;
    private int readTimeout = 60000;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        if (retries < 0) {
            throw new IllegalArgumentException("retries must not be negative");
        }
        this.retries = retries;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        if (connectTimeout < 1) {
            throw new IllegalArgumentException("connectTimeout must be greater than zero");
        }
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        if (readTimeout < 1) {
            throw new IllegalArgumentException("readTimeout must be greater than zero");
        }
        this.readTimeout = readTimeout;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HostDefinition that = (HostDefinition) o;
        return port == that.port &&
            ssl == that.ssl &&
            retries == that.retries &&
            connectTimeout == that.connectTimeout &&
            readTimeout == that.readTimeout &&
            Objects.equals(host, that.host) &&
            Objects.equals(user, that.user) &&
            Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, user, password, ssl, retries, connectTimeout, readTimeout);
    }

}
