package com.aristotlecap.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

import com.aristotlecap.pipeline.models.Validation
import com.aristotlecap.pipeline.models.*
import java.util.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class HelmModel implements Serializable {

    static HelmModel fromJsonString(String text) {
        JsonSlurper parser = new JsonSlurper()
        Map json = parser.parseText(text)
        List<String> nonProdEnvsArray = HelmModel.getArray(json.nonProdEnvs)
        List<String> qaEnvsArray = HelmModel.getArray(json.qaEnvs)
        List<HelmChart> chartsArray = HelmModel.fromList(json.charts)
        return new HelmModel(
          text,
          json.builderTag,
          nonProdEnvsArray,
          qaEnvsArray,
          json.prodEnv,
          chartsArray,
          convertHashMap(json.helmRepos),
          convertHashMap(json.dockerfiles),
          convertHashMap(json.secrets),
          convertHashMap(json.kuberneteSecrets),
          json.releaseVersion)
    }

    final String text;
    final String builderTag;
    final List<String> nonProdEnvs;
    final List<String> qaEnvs;
    final String prodEnv;
    final List<HelmChart> charts;
    final HashMap<String, String> helmRepos;
    final HashMap<String, String> dockerfiles;
    final HashMap<String, String> secrets;
    final HashMap<String, String> kuberneteSecrets;
    String releaseVersion;

    HelmModel(String text, String builderTag, List<String> nonProdEnvs,
        List<String> qaEnvs, String prodEnv, List<HelmChart> charts, HashMap<String, String> helmRepos,
        HashMap<String, String> dockerfiles, HashMap<String, String> secrets, HashMap<String, String> kuberneteSecrets, String releaseVersion) {
        this.text = text
        this.builderTag = builderTag
        this.nonProdEnvs = nonProdEnvs
        this.qaEnvs = qaEnvs
        this.prodEnv = prodEnv
        this.charts = charts
        this.helmRepos = helmRepos
        this.dockerfiles = dockerfiles
        this.secrets = secrets
        this.kuberneteSecrets = kuberneteSecrets
        this.releaseVersion = releaseVersion
    }

    static List<String> getArray(arr) {
      def ArrayList<String> list = new ArrayList<String>()
      for (a in arr) {
        list.add(a)
      }
      return list
    }

    static List<HelmChart> fromList(lst) {
      def ArrayList<HelmChart> list = new ArrayList<HelmChart>()
      for (a in lst) {
        list.add(HelmChart.fromMap(a))
      }
      return list
    }

    @NonCPS
    public String toString() {
       return "{builderTag:" + this.builderTag + "," +
              " nonProdEnvs:" + fromStringList(this.nonProdEnvs) + "," +
              " qaEnvs:" + fromStringList(this.qaEnvs) + "," +
              " prodEnv:" + this.prodEnv + "," +
              " charts:" + fromList2(this.charts) + "," +
              " helmRepos:" + this.helmRepos + "," +
              " dockerfiles:" + this.dockerfiles + "," +
              " secrets:" + this.secrets + "," +
              " kuberneteSecrets:" + this.kuberneteSecrets + "," +
              " releaseVersion:" + this.releaseVersion + "}"
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
    String fromList2(list) {
      String s
      for(d in list) {
        s = (s?.trim())? s + "," + d.toString(): d.toString()
      }
      return s
    }

    @NonCPS
    static HashMap<String, String> convertHashMap(Map m) {
      HashMap<String, String> map = new HashMap<String, String>()
      if (m != null) {
        m.each { key, val ->
          def v = val
          map.put(key, v)
        }
      }
      return map
    }

}
