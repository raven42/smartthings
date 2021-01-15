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
	definition (name: "ISY Switch", namespace: "raven42", author: "David Hegland") {
		capability "Actuator"
		capability "Switch"
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

def statusUpdate(xml) {
	def level = device.getDataValue("status")
    if (xml.status) {
    	level = xml.status.text() as int

        log.debug "dm.statusUpdate() device.status:${device.getDataValue("status")} -> status:${level} xml:${xml}"
        if (device.getDataValue("status") != level) {
            if (level > 0) {
                sendEvent(name: "switch", value: "on")
            } else {
                sendEvent(name: "switch", value: "off")
            }
        }
    }
}

def update() {
	log.debug "dm.update() status:${device.getDataValue("status")}"
    def level = device.getDataValue("status") as int
    if (level > 0) {
        sendEvent(name: "switch", value: "on")
    } else {
        sendEvent(name: "switch", value: "off")
    }
}

def parseNode(msg) {
    msg?.property.each { prop ->
    	if (prop.@id.equals("ST") && prop.@value != device.getDataValue("status")) {
        	device.updateDataValue("status", prop.@value)
            if (prop.@value > 0) {
            	sendEvent(name: "switch", value: "on")
            } else {
            	sendEvent(name: "switch", value: "off")
            }
        }
    }
}

def parseStatus(msg) {
	if (parent.getDebug()) {
    	log.debug "dm.parseStatus() msg:[${msg}]"
    }
    msg?.property.each { prop ->
		if (prop.@id.equals("ST") && prop.@value != device.getDataValue("status")) {
        	log.debug "dm.parseStatus() status:${device.getDataValue("status")} -> ${prop.@value}"
            device.updateDataValue("status", prop.@value)
            if (prop.@value > 0) {
            	sendEvent(name: "switch", value: "on")
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