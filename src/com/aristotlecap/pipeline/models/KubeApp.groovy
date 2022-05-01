package com.aristotlecap.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

import com.aristotlecap.pipeline.models.Validation
import com.aristotlecap.pipeline.models.*

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class KubeApp implements Serializable {

    static KubeApp fromJsonString(String text) {
        JsonSlurper parser = new JsonSlurper()
        Map json = parser.parseText(text)
        Map<String, String> map = KubeApp.getMap(json.secrets)
        return new KubeApp(
          json.dockerFile,
          map,
          json.helmChartVersion,
          json.appType,
          json.enabled)
    }

    static KubeApp fromMap(Map map) {
        Map<String, String> m = KubeApp.getMap(map.secrets)
        return new KubeApp(
          map.dockerFile,
          m,
          map.helmChartVersion,
          map.appType,
          map.enabled)
    }

    final String dockerFile;
    final Map<String, String> secrets;
    final String helmChartVersion;
    final String appType;
    boolean enabled;

    KubeApp(String dockerFile, Map<String, String> secrets, String helmChartVersion,
        String appType, boolean enabled) {
        this.dockerFile = dockerFile
        this.secrets = secrets
        this.helmChartVersion = helmChartVersion
        this.appType = appType
        this.enabled = enabled
    }

    static getMap(m) {
      Map<String, String> map = [:]
      m.each { key, val ->
        map[key]=val
      }
      return map
    }

    @NonCPS
    public String toString() {
        String s
        this.secrets.each{ k, v ->
          s = (s?.trim())? s + ", ${k}:${v}": "${k}:${v}"
        }
        return "{ dockerFile:" + this.dockerFile + "," +
               "  helmChartVersion:" + this.helmChartVersion + "," +
               "  appType:" + this.appType + "," +
               "  enabled:" + this.enabled + "," +
               "  secrets:" + s + "}"
    }


}
