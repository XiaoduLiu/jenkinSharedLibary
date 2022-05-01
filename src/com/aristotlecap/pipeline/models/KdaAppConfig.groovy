package com.aristotlecap.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

import com.aristotlecap.pipeline.models.Validation
import com.aristotlecap.pipeline.models.*
import java.util.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class KdaAppConfig implements Serializable {

    static KdaAppConfig fromJsonString(String text) {
        JsonSlurper parser = new JsonSlurper()
        Map json = parser.parseText(text)
        List<String> nonProdEnvsArray = KdaAppConfig.getArray(json.nonProdEnvs)
        List<String> qaEnvsArray = KdaAppConfig.getArray(json.qaEnvs)
        List<String> dependenciesArray = KdaAppConfig.getArray(json.dependencies)
        List<KdaApp> kdaAppArray = KdaAppConfig.fromList1(json.kdaApps)
        List<KubeApp> kubeAppArray = KdaAppConfig.fromList2(json.kubeApps)
        return new KdaAppConfig(
          text,
          json.builderTag,
          nonProdEnvsArray,
          qaEnvsArray,
          json.prodEnv,
          json.drEnv,
          dependenciesArray,
          json.secretsTemplate,
          kdaAppArray,
          kubeAppArray)
    }

    final String text;
    final String builderTag;
    final List<String> nonProdEnvs;
    final List<String> qaEnvs;
    final String prodEnv;
    final String drEnv;
    final List<String> dependencies;
    final String secretsTemplate;
    final List<KdaApp> kdaApps;
    final List<KubeApp> kubeApps;


    KdaAppConfig(String text, String builderTag, List<String> nonProdEnvs,
        List<String> qaEnvs, String prodEnv, String drEnv, List<String> dependencies,
        secretsTemplate, List<KdaApp> kdaApps, List<KubeApp> kubeApps) {
        this.text = text
        this.builderTag = builderTag
        this.nonProdEnvs = nonProdEnvs
        this.qaEnvs = qaEnvs
        this.prodEnv = prodEnv
        this.drEnv = drEnv
        this.dependencies = dependencies
        this.secretsTemplate = secretsTemplate
        this.kdaApps = kdaApps
        this.kubeApps = kubeApps
    }

    static List<String> getArray(arr) {
      def ArrayList<String> list = new ArrayList<String>()
      for (a in arr) {
        list.add(a)
      }
      return list
    }

    static List<KdaApp> fromList1(lst) {
      def ArrayList<KdaApp> list = new ArrayList<KdaApp>()
      for (a in lst) {
        list.add(KdaApp.fromMap(a))
      }
      return list
    }

    static List<KubeApp> fromList2(lst) {
      def ArrayList<KubeApp> list = new ArrayList<KubeApp>()
      for (a in lst) {
        list.add(KubeApp.fromMap(a))
      }
      return list
    }

    @NonCPS
    public String toString() {
       return "{builderTag:" + this.builderTag + "," +
              " nonProdEnvs:" + fromStringList(this.nonProdEnvs) + "," +
              " qaEnvs:" + fromStringList(this.qaEnvs) + "," +
              " prodEnv:" + this.prodEnv + "," +
              " drEnv:" + this.drEnv + "," +
              " dependencies:" + fromStringList(this.dependencies) + "," +
              " secretsTemplate:" + this.secretsTemplate + "," +
              " kdaApps:" + fromList(this.kdaApps) + "," +
              " kubeApps:" + fromList(this.kubeApps) + "}"
    }

    @NonCPS
    String fromStringList(list) {
      String s
      for(d in list) {
        s = (s?.trim())? s + "," + d: d
      }
      return s
    }

    @NonCPS
    String fromList(list) {
      String s
      for(d in list) {
        s = (s?.trim())? s + "," + d.toString(): d.toString()
      }
      return s
    }

    @NonCPS
    Map<String, Boolean> getAppDeploySelectionMap() {
      def Map<String, String> map = new HashMap<String, Boolean>()
      for (a in this.kdaApps) {
        def folderName = a.flinkJarLocation.split('/')[0]
        map.putAt(folderName, a.enabled)
      }
      for (a in this.kubeApps) {
        def folderName = a.dockerFile.split('/')[0]
        map.putAt(folderName, a.enabled)
      }
      return map
    }

    @NonCPS
    void applyAppDeploySelection(input) {
      for (a in this.kdaApps) {
        def folderName = a.flinkJarLocation.split('/')[0]
        a.enabled = input[folderName]
      }
      for (a in this.kubeApps) {
        def folderName = a.dockerFile.split('/')[0]
        a.enabled = input[folderName]
      }
    }

}
