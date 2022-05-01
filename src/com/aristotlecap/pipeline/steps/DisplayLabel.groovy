package com.aristotlecap.pipeline.steps

import com.aristotlecap.pipeline.models.*

def displayLabel(branchName, builderNumber) {
    currentBuild.displayName = branchName + '-' + builderNumber
}

def displayLabel(branchName, builderNumber, crNumber) {
    currentBuild.displayName = branchName + '-' + builderNumber + '-' + crNumber
}

def displayLabel(branchName, builderNumber, releaseVersion, crNumber) {
    currentBuild.displayName = branchName + '-' + builderNumber + '-' + releaseVersion + '-' + crNumber
}
