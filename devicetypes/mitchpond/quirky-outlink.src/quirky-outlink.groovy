/**
 *  Copyright 2015 Mitch Pond
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

metadata {
    definition (name: "Quirky Outlink", namespace: "mitchpond", author: "Mitch Pond") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Energy Meter"
        capability "Sensor"
        capability "Switch"
        
        attribute "energyDisplay", "string"
        attribute "powerDisplay", "string"
        
        command "resetEnergyUsage"
        
		//01 0104 0002 00 07 0000 0003 0006 0005 0004 FC20 0702 01 0019
        fingerprint endpointId: "01", profileId: "0104", inClusters: "0000, 0003, 0006, 0005, 0004, FC20, 0702", outClusters: "0019", manufacturer: "Quirky", model: "ZHA Smart Plug"
    }

    tiles(scale: 2){

    	multiAttributeTile(name: "switch", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
        	tileAttribute("device.switch", key: "PRIMARY_CONTROL") {
            	attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.switch.on", backgroundColor:"#79b821", nextState:"turningOff"
            	attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.switch.off", backgroundColor:"#ffffff", nextState:"turningOn"
                attributeState "turningOn", label:'${name}', action:"switch.off", icon:"st.switches.switch.on", backgroundColor:"#79b821", nextState:"turningOff"
                attributeState "turningOff", label:'${name}', action:"switch.on", icon:"st.switches.switch.off", backgroundColor:"#ffffff", nextState:"turningOn"
            }
            tileAttribute("energyDisplay", key: "SECONDARY_CONTROL") {
            	attributeState "default", label:'Energy used: ${currentValue}', unit: "kWh"
            }
        }
		/*
        standardTile("switch", "device.switch", width: 2, height: 2, canChangeIcon: true){
            state "on", label:'${name}', action:"switch.off", icon:"st.switches.switch.on", backgroundColor:"#79b821", nextState:"turningOff"
            state "off", label:'${name}', action:"switch.on", icon:"st.switches.switch.off", backgroundColor:"#ffffff", nextState:"turningOn"
            state "turningOn", label:'${name}', action:"switch.off", icon:"st.switches.switch.on", backgroundColor:"#79b821", nextState:"turningOff"
            state "turningOff", label:'${name}', action:"switch.on", icon:"st.switches.switch.off", backgroundColor:"#ffffff", nextState:"turningOn"
        }
        */
        valueTile("energyDisplay", "device.energyDisplay", width: 4, height: 1, decoration: "flat") {
        	state "default", label:'Energy used: ${currentValue}', unit: "kWh"
        }
        standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        
        standardTile("resetUsage", "command.resetEnergyUsage", decoration: "flat", width: 2, height: 2){
        	state "default", action: "resetEnergyUsage", icon: "st.secondary.configure"
        }
        valueTile("powerDisplay", "device.powerDisplay", decoration: "flat", width: 4, height: 1){
        	state "default", label: 'Power: ${currentValue}', unit: "W"
        }
        main "switch"
        details(["switch","energyDisplay","refresh","powerDisplay","resetUsage"])
    }
}

// Parse incoming device messages to generate events
def parse(String description) {
    log.debug "description is $description"
    def resultMap = zigbee.getKnownDescription(description)
    
    if (resultMap) {
        if (resultMap.type == "update") {
            log.info "$device updates: ${resultMap.value}"
        }
        else {
            createEvent(name: resultMap.type, value: resultMap.value)
        }
    }
    else {
        def descMap = zigbee.parseDescriptionAsMap(description)
        log.debug descMap
        if (descMap.cluster == "0702" && descMap.attrId == "0000"){
        	log.debug "Parsing kWh..."
            parseZSEMeteringReport(descMap)
        }
    }
}

def parseZSEMeteringReport(descMap){
    //log.debug "In ZSE parse..."
    def energyUsed = decodeHexEnergyUsage(descMap.value)
    def isStateChange = !(device.currentValue("energy") == energyUsed)
    def energyEvent = [createEvent(name: "energy", value: energyUsed)]
    log.debug isStateChange
    log.debug energyEvent.date
    def results = energyEvent +
    createEvent(name: "energyDisplay", 
                value: String.format("%6.3f kWh",energyUsed),
                isStateChange: true, 
                displayed: false) +
    createEvent(name: "powerDisplay", 
                value: String.format("%4.2f W",(isStateChange? getInstantDemand() : 0.0)),
                isStateChange: true, 
                displayed: false)
    log.debug results
    return results
}

def off() {
    zigbee.off()
}

def on() {
    zigbee.on()
}

//This currently does nothing.... :(
def resetEnergyUsage() {
	configure()
	def timeInMillis = now()/1000 as int
    def hexTime = Integer.toHexString(Integer.reverseBytes(timeInMillis))
    log.debug hexTime
	//zigbee.command(0x0702,0x1,"0000")
    //["raw 0x0702 {04 FFFF 00 15 0000FF}",
    //"send 0x${device.deviceNetworkId} 1 1"]
}

def refresh() {
	//getInstantDemand()
	zigbee.onOffRefresh() +
    zigbee.readAttribute(0x0702, 0x0000)
}

def configure() {
    log.debug "Configuring Reporting and Bindings."
    zigbee.onOffConfig() + 
    zigbee.configureReporting(0x0702, 0, 0x25, 30, 60, 0x01)+ 
    refresh() 
}

private decodeHexEnergyUsage(String hexValue) {
	//The Outlink reports in kWh, with a conversion factor of 1/3600000
	def rawValue = Integer.parseInt(hexValue, 16)
    def scaledValue = rawValue/3600000
    return scaledValue
}

private getInstantDemand() {
	try {
        //Since the device does not support the reporting of instantaneous demand, we need to derive this
        def recentEvents = device.statesSince("energy",new Date()-1).collect {[value: it.value as float, date: it.date]}
        def deltaE = (recentEvents[0].value - recentEvents[1].value)*1000 // W*h
        def deltaT = (recentEvents[0].date.getTime() - recentEvents[1].date.getTime())/3600000 // h

        return deltaE/deltaT
    
    } catch (Exception e) {
    	log.debug recentEvents
    }
}