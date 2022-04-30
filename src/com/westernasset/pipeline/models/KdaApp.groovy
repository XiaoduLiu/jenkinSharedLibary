package com.westernasset.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

import com.westernasset.pipeline.models.Validation
import com.westernasset.pipeline.models.*

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class KdaApp implements Serializable {

    static KdaApp fromJsonString(String text) {
        JsonSlurper parser = new JsonSlurper()
        Map json = parser.parseText(text)
        List<String> dependenciesArray = KdaApp.getArray(json.dependencies)
        return new KdaApp(json.flinkJarLocation, json.enabled, json.resetFlag,
          json.appType, dependenciesArray)
    }

    static KdaApp fromMap(Map map) {
        List<String> dependenciesArray = KdaApp.getArray(map.dependencies)
        return new KdaApp(map.flinkJarLocation, map.enabled, map.resetFlag,
          map.appType, dependenciesArray)
    }

    final String flinkJarLocation;
    boolean enabled;
    final boolean resetFlag;
    final String appType;
    final List<String> dependencies;

    KdaApp(String flinkJarLocation, boolean enabled, boolean resetFlag,
           String appType, List<String> dependencies) {
        this.flinkJarLocation = flinkJarLocation
        this.enabled = enabled
        this.resetFlag = resetFlag
        this.appType = appType
        this.dependencies = dependencies
    }

    static List<String> getArray(arr) {
      def ArrayList<String> list = new ArrayList<String>()
      for (a in arr) {
        list.add(a)
      }
      return list
    }

    @NonCPS
    public String toString() {
        String s
        for(d in this.dependencies) {
          s = (s?.trim())? s + "," + d: d
        }
        return "{ flinkJarLocation:" + this.flinkJarLocation + "," +
               "  enabled:" + this.enabled + "," +
               "  resetFlag:" + this.resetFlag + "," +
               "  appType:" + this.appType + "," +
               "  dependencies:" + s + "}"
    }

}
