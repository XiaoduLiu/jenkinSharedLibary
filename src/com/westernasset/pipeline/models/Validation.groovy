package com.westernasset.pipeline.models

import com.cloudbees.groovy.cps.NonCPS

class Validation {

    static String format(List<String> errors) {
        return errors.join('\n')
    }

    private final List<String> requiredVariables
    private final List<List<String>> requiredOneOfVariables
    private final Map requiredTypes
    private final List<List<String>> matchedLists

    Validation() {
        requiredVariables = []
        requiredOneOfVariables = []
        requiredTypes = [:]
        matchedLists = []
    }

    Validation(String... requiredVariables) {
        this()
        requiredVariables.each { variable ->
            require(variable, CharSequence)
        }
    }

    @NonCPS
    Validation require(String variable, Class... types) {
        requiredVariables.push(variable)
        if (types.size() > 0) {
            requireType(variable, types)
        }
        return this
    }

    @NonCPS
    Validation requireOneOf(String... variables) {
        requiredOneOfVariables.add(variables as List)
        return this
    }

    @NonCPS
    Validation requireType(String variable, Class... types) {
        requiredTypes[variable] = requiredTypes[variable] ?: []
        requiredTypes[variable].addAll(types)
        return this
    }

    @NonCPS
    Validation matchLists(String... lists) {
        matchedLists.add(lists)
        return this
    }

    List<String> check(Map config) {
        List errors = []
        errors.addAll(validateRequiredVariables(config))
        errors.addAll(validateRequiredOneOfVariables(config))
        errors.addAll(validateRequiredTypes(config))
        errors.addAll(validateMatchedLists(config))
        return errors
    }

    List<String> validateRequiredVariables(Map config) {
        return requiredVariables
            .findAll { variable -> config[variable] == null }
            .collect { variable -> missingRequiredField(variable) }
    }

    List<String> validateRequiredOneOfVariables(Map config) {
        List<String> errors = []
        requiredOneOfVariables.each { variables ->
            List<String> values = variables.collect { variable -> config[variable] }
                .unique()
            if (values == [null]) {
                errors.add("None of required variable set found: ${variables}".toString())
            }
        }
        return errors
    }

    List<String> validateRequiredTypes(Map config) {
        return requiredTypes
            .findAll { variable, types -> config[variable] != null && !types.any { config[variable] in it } }
            .collect { variable, types -> invalidType(variable, types) }
    }

    List<String> validateMatchedLists(Map config) {
        List<String> errors = []
        matchedLists.each { matchedList ->
            List lengths = []
            matchedList.each { variable ->
                if (config[variable] instanceof List) {
                    lengths.add(config[variable].size())
                } else if (config[variable] != null) {
                    errors.add(invalidType(variable, [List.class]))
                }
            }
            if (lengths.unique().size() > 1) {
                errors.add("Mismatched lengths between fields: ${matchedList.join(', ')}".toString())
            }
        }
        return errors
    }

    private String missingRequiredField(String variable) {
        return "Missing required field '${variable}'".toString()
    }

    private String invalidType(String variable, def types) {
        return "Invalid type for field '${variable}' expected: ${types.join(', ')}".toString()
    }

}
