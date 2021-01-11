/**
 *  Copyright 2020 David Hegland
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */
metadata {
	definition (name: "ISY Dimmer", namespace: "raven42", author: "David Hegland") {
		capability "Actuator"
		capability "Switch"
		capability "Switch Level"
        capability "Polling"
        capability "Refresh"
        command "ParseStatus", ["string"]
	}

	simulator {
		// TODO: define status and reply messages here
	}

	tiles (scale: 2) {
		multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true){
			tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
				attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.switch.on", nextState:"off", backgroundColor: "#79b821"
				attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.switch.off", nextState:"on", backgroundColor: "#ffffff"
			}
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action:"switch level.setLevel"
            }
		}

		standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
		}
	}
	main(["switch"])
	details(["switch", "refresh"])
}


////////////////////////////////////////////////////////////////////////////////////////////////////
// Response Parsing
def parse(description) {
	log.trace "parse($description)"

	def results = []
    def msg = parseLanMessage(description)
    def json = msg.json
    log.trace "MESSAGE Result: $msg"
    if (msg.status == 200 || msg.header.startsWith('POST')) {
    	log.debug "JSON Result: $json"
        json.each {
        	def n = it.getKey()
        	if (n != "status") {
            	def v = it.getValue()
        		results << createEvent(name: n, value: v)
        	}
        }
    }

	results
}

def parseResponse(resp) {
	def json = resp.json
    log.debug "JSON Result: $json"
    json.each {
    	def n = it.key
        if (n != "status") {
            def v = it.value
            sendEvent(name: n, value: v)
        }
    }
}

def parseStatus(msg) {
	if (parent.getDebug()) {
    	log.debug "dm.parseStatus() msg:[${msg}]"
    }
    msg?.property.each { prop ->
		if (prop.@id.equals("OL") && prop.@value != device.getDataValue("onLevel")) {
        	log.debug "dm.parseStatus() onLevel:${device.getDataValue("onLevel")} -> ${prop.@value}"
            device.updateDataValue("onLevel", prop.@value)
        } else if (prop.@id.equals("RR") && prop.@value != device.getDataValue("rampRate")) {
        	log.debug "dm.parseStatus() rampRate:${device.getDataValue("rampRate")} -> ${prop.@value}"
            device.updateDataValue("rampRate", prop.@value)
        } else if (prop.@id.equals("ST") && prop.@value != device.getDataValue("status")) {
        	log.debug "dm.parseStatus() status:${device.getDataValue("status")} -> ${prop.@value}"
            device.updateDataValue("status", prop.@value)
            if (prop.@value > 0) {
            	sendEvent(name: "switch", value: "on")
                sendEvent(name: "level", value: "${isy2stLevel(prop.@value)}")
            } else {
            	sendEvent(name: "switch", value: "off")
            }
        }
    }
}


/////////////////////////////////////////////////////////////////////////////////////////////////////
// Switch commands

def onResp(resp) {
	log.debug "dm.onResp() resp:[${resp}]"
    def description = resp.description
    def msg = parseLanMessage(description)
    
    //log.trace "parsedResponse:[${msg}]"
    if (msg.status == 200) {
    	sendEvent(name: "switch", value: "on")
    	sendEvent(name: "level", value: isy2stLevel(device.getDataValue("onLevel")))
        device.updateDataValue("status", device.getDataValue("onLevel"))
    } else {
    	log.warning "dm.on() failed status:${msg.status} resp:[${msg}]"
    }
}
def on() {
	def dni = device.getDataValue("deviceNetworkId")
	log.debug "dm.on() called for device:${dni}"
    
    parent.restGet(device, "/rest/nodes/${device.getDataValue("nodeAddress").replaceAll(" ", "%20")}/cmd/DON", [callback:onResp])
}

def offResp(resp) {
	log.debug "dm.offResp() resp:[${resp}]"
    def description = resp.description
    def msg = parseLanMessage(description)
    
    //log.trace "parsedResponse:[${msg}]"
    if (msg.status == 200) {
    	sendEvent(name: "switch", value: "off")
        device.updateDataValue("status", "0")
    } else {
    	log.warning "dm.on() failed status:${msg.status} resp:[${msg}]"
    }
}
def off() {
	def dni = device.getDataValue("deviceNetworkId")
	log.debug "dm.off() called for device:${dni}"

    parent.restGet(device, "/rest/nodes/${device.getDataValue("nodeAddress").replaceAll(" ", "%20")}/cmd/DOF", [callback:offResp])
}

def setLevelResp(resp) {
	log.debug "dm.setLevelResp() resp:[${resp}]"
    def description = resp.description
    def msg = parseLanMessage(description)
    
    //log.trace "parsedResponse:[${msg}]"
    if (msg.status == 200) {
    	sendEvent(name: "switch", value: "on")
        sendEvent(name: 'level', value: "${state.pendingLevel}")
        device.updateDataValue("status", "${st2isyLevel(state.pendingLevel)}")
    } else {
    	log.warning "dm.on() failed status:${msg.status} resp:[${msg}]"
    }
}
def setLevel(level) {
	def dni = device.getDataValue("deviceNetworkId")
	def isyLevel = st2isyLevel(level)
    isyLevel = isyLevel.toInteger()
	log.debug "sm.setLevel() called for device:${dni} level:${level} isyLevel:${isyLevel}"
    state.pendingLevel = level

	if (level == 0) {
    	parent.restGet(device, "/rest/nodes/${device.getDataValue("nodeAddress").replaceAll(" ", "%20")}/cmd/DOF", [callback:setOffResp])
    } else {
    	parent.restGet(device, "/rest/nodes/${device.getDataValue("nodeAddress").replaceAll(" ", "%20")}/set/DON/${isyLevel}", [callback:setLevelResp])
    }
}


//////////////////////////////////////////////////////////////////////////////////
// Helper routines
def isy2stLevel(isyLevel) {
	def stLevel = isyLevel.toFloat() * 100.0 / 255.0
    stLevel as int
}
def st2isyLevel(stLevel) {
	def isyLevel = stLevel.toFloat() * 255.0 / 100.0
    isyLevel as int
}