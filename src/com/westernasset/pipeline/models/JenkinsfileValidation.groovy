package com.westernasset.pipeline.models

class JenkinsfileValidation {

    static String format(List<String> errors) {
        return errors.join('\n')
    }

    private final List<String> requiredVariables
    private final Map requiredTypes
    private final List<List<String>> matchedLists

    JenkinsfileValidation() {
        requiredVariables = []
        requiredTypes = [:]
        matchedLists = []
    }

    JenkinsfileValidation(String... requiredVariables) {
        this()
        requiredVariables.each { variable ->
            require(variable)
        }
    }

    void require(String variable, Class... types) {
        requiredVariables.push(variable)
        if (types.size() > 0) {
            requireType(variable, types)
        }
    }

    void requireType(String variable, Class... types) {
        requiredTypes[variable] = requiredTypes[variable] ?: []
        requiredTypes[variable].addAll(types)
    }

    void matchLists(String... lists) {
        matchedLists.add(lists)
    }

    List<String> validate(Map config) {
        List errors = []
        errors.addAll(validateRequiredVariables(config))
        errors.addAll(validateRequiredTypes(config))
        errors.addAll(validateMatchedLists(config))
        return errors
    }

    List<String> validateRequiredVariables(Map config) {
        return requiredVariables
            .findAll { variable -> config[variable] == null }
            .collect { variable -> missingRequiredField(variable) }
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
