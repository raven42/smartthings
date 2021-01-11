/**
 *  ISY Connect
 *
 *  Copyright 2021 David Hegland
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

definition(
    name: "ISY 994i (Connect)",
    namespace: "raven42",
    author: "David Hegland",
    description: "Link ISY hub",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png") {
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Preferences Section
preferences {
	page(name: "mainPage")
    page(name: "discoveryPage")
}

def mainPage() {
	log.debug "mainPage()"
    def refreshInterval = 5
    def refreshCount = !state.refreshCount ? 0 : state.refreshCount as int
    
    init()
    
    if (!selectedDevice) {
   		discover()
        
        return dynamicPage(name:"mainPage", title:"ISY Connect", nextPage:"", refreshInterval:refreshInterval, install:false, uninstall:true) {
            section ("ISY Hub Selection") {
                href(name: "deviceDiscovery", title: "Search for ISY", description: "Perform discovery of ISY using UPnP", page: "discoveryPage")
            }
        }
    } else {
        state.refreshCount = refreshCount + 1
        
      	if ((refreshCount % 5) == 0) {
            queryNodes()
        }
        if (refreshCount == 0) {
        	getStatus()
        }

        def nodes = getNodes()
        def nodeNames = nodes.collect { entry -> entry.value.name }
        if (nodeNames.size() != state?.nodeNames?.size()) {
        	state.nodeNames = nodeNames
        	log.debug "found ${nodes.size()} nodes"
        }
		def variables = getVariables()
		def variableNames = variables.collect { entry -> entry.value.name }
		if (variableNames.size() != state?.variableNames?.size()) {
			state.variableNames = variableNames
			log.debug "found ${variableNames.size()} variables"
		}
        
        return dynamicPage(name:"mainPage", title:"ISY Connect", nextPage:"", refreshInterval:refreshInterval, install:true, uninstall:true) {
        	section("ISY Global Settings") {
            	input "updateInterval", "number", required: true, title: "Update Interval\nSeconds between polling intervals", defaultValue:30
                input "debug", "bool", required: true, title: "Enable debug"
            }
            section("Select nodes...") {
                input "selectedNodes", "enum", required:false, title:"Select Nodes \n(${state?.nodeNames?.size() ?: 0} found)", multiple:true, options:state?.nodeNames
            }
			section("SmartThings Mode Handling") {
				location.modes.each { mode ->
					input "modeMap${mode}", "enum", required:false, title:"[${mode}] Mode Mapping", multiple:false, options:state?.variableNames
				}
			}
        }
    }
}

def discoveryPage() {
	log.debug "discoveryPage()"
    
	int hubRefresh = !state.hubRefresh ? 0 : state.hubRefresh as int
	def refreshInterval = 3
	state.hubRefresh = hubRefresh + refreshInterval
    
    if ((hubRefresh % 15) == 0) {
 		discover()
    }
 	
    def devices = getDevices()
 	def deviceNames = devices.collect { it.value.ip }
    if (deviceNames.size() != state?.deviceNames?.size()) {
    	state.deviceNames = deviceNames
        log.debug "found ${state.deviceNames.size()} devices"
    }
     
 	return dynamicPage(name:"discoveryPage", title:"Discovery Started", nextPage:"", refreshInterval:refreshInterval, install:false, uninstall:false) {
 		section("Please wait while we discover your device. Select your device below once discovered.") {
 			input "selectedDevice", "enum", required:true, title:"Select Devices \n(${state?.deviceNames?.size() ?: 0} found)", multiple:false, options:state?.deviceNames
 		}
        section("ISY Credentials") {
        	input "username", "text", title:"ISY Username", required:true
            input "password", "password", title:"ISY Password", required:true
        }
 	}
}

def init() {
    if (!state.initialized) {
        log.debug "init()"
    	state.initialized = true
        settings.debug = false
    	subscribe(location, "ssdpTerm.urn:udi-com:device:X_Insteon_Lighting_Device:1", ssdpHandler)
        subscribe(location, null, responseHandler, [filterEvents:false])
    }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////
// Network Layer Handler and Parser Routines

def ssdpHandler(evt) {
    log.debug('ssdpHandler() event received:' + evt.description)

    def description = evt.description
    def hub = evt?.hubId
    def parsedEvent = parseLanMessage(description)
    parsedEvent << ["hub":hub]

    // Force port 80 (0x50)
    parsedEvent.port = '0050'
    parsedEvent << ["ip" : convertHexToIP(parsedEvent.networkAddress)]

    if (parsedEvent?.ssdpTerm?.contains("udi-com:device:X_Insteon_Lighting_Device:1")) {
        def devices = getDevices()

        if (!(devices."${parsedEvent.ssdpUSN.toString()}")) { //if it doesn't already exist
            log.debug('Parsed Event: ' + parsedEvent)
            devices << ["${parsedEvent.ssdpUSN.toString()}":parsedEvent]
        } else { // just update the values
            def d = devices."${parsedEvent.ssdpUSN.toString()}"
            boolean deviceChangedValues = false

            if (d.ip != parsedEvent.ip || d.port != parsedEvent.port) {
                d.ip = parsedEvent.ip
                d.port = parsedEvent.port
                deviceChangedValues = true
            }

            if (deviceChangedValues) {
                def children = getAllChildDevices()
                children.each {
                    if (it.getDeviceDataByName("mac") == parsedEvent.mac) {
                        //it.subscribe(parsedEvent.ip, parsedEvent.port)
                    }
                }
            }

        }
    }
}

def parse(evt) {
	log.debug "parse() evt:[${evt}]"
}

def responseHandler(evt) {
	log.debug "responseHandler() evt:[${evt}]"
	def hub = evt?.hubId
	def parsedEvent = parseLanMessage(evt.description)
    parsedEvent << ["hub":hub]
    def child = getChildDevices()?.find {
    	(it.getDataValue("networkAddress") == parsedEvent?.networkAddress) ||
        (it.getDataValue("mac") == parsedEvent?.mac && (parsedEvent?.headers?.sid == null || it.deviceNetworkId == parsedEvent?.headers?.sid))
    }
    if (child) {
    	child.parseResponse(parsedEvent)
    }
}


///////////////////////////////////////////////////////////////////////////////////////////////////////////
// ISY Hub (device) Routines

def discover() {
	log.debug('Performing discovery')
    sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:udi-com:device:X_Insteon_Lighting_Device:1", physicalgraph.device.Protocol.LAN))
}

def getIsyHub() {
    def selDev
    def devices = getDevices()
    selDev = devices.find { it.value.ip == selectedDevice }
    selDev
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////
// ISY Node Query Routines

def parseQueryNodes(resp) {
	def nodes = getNodes()
    
    log.debug "parseResponse() ${resp.description}"
    
    def xml = new XmlParser().parseText(resp.body)
    //log.debug "xml:[${xml}]"
    def xmlNodes = xml.node
    def printed = 0
    //log.debug "xmlNodes:[${xmlNodes}]"
    xmlNodes.each { xmlNode ->
    	if (!(nodes[xmlNode.address.text()])) {
        	def node = [:]
        	node.address = xmlNode.address.text()
        	node.name = xmlNode.name.text()
            node.type = xmlNode.type.text()
            node.deviceNetworkId = "ISY:${node.address}"
            node.status = 0
    		xmlNode?.property.each { prop ->
            	if (prop.@id.equals('ST')) {
            		node.status = prop.@value
                }
            }
        
            state.nodes[node.address] = node
            
            if ((printed % 50) == 0) {
                log.debug "adding node:[${node}]"
            }
            printed += 1
        }
    }
}

def queryNodes() {
    def isy = getIsyHub()
    if (!isy) { return }
    def host = isy.value.networkAddress + ":" + isy.value.port
    def auth = getAuthorization()
        
    log.debug "attempting to get nodes from ${host}"
    
    sendHubCommand(new physicalgraph.device.HubAction(
        	'method': 'GET',
        	'path': '/rest/nodes',
        	'headers': [
        	    'HOST': host,
        	    'Authorization': auth
        	], null, [callback:parseQueryNodes]))
}

def parseStatus(msg) {
	def nodes = getNodes()
    
    // log.debug "parseStatus() desc: ${msg.description}"
	// log.debug "parseStatus() body: ${msg.body}"
    
    def xml = new XmlParser().parseText(msg.body)
    def xmlNodes = xml.nodes
    xml.each { xmlNode ->
    	def node = nodes.find { it.value.address == xmlNode.@id }
        if (node) {
        	def d = getAllChildDevices()?.find { it.device.deviceNetworkId == node.value.deviceNetworkId }
        	if (d) {
            	d.parseStatus(xmlNode)
            }
        }
    }
}

def getStatus() {
    def isy = getIsyHub()
    if (!isy) { return }
    def host = isy.value.networkAddress + ":" + isy.value.port
    def auth = getAuthorization()
    
    if (settings.debug) {
    	log.debug "debug:${settings.debug} attempting to get nodes from ${host}"
    }
    
    sendHubCommand(new physicalgraph.device.HubAction(
        	'method': 'GET',
        	'path': '/rest/status',
        	'headers': [
        	    'HOST': host,
        	    'Authorization': auth
        	], null, [callback:parseStatus]))
}

def getStatusLoop() {
	getStatus()
    if (settings.updateInterval < 10) {
    	settings.updateInterval = 10
    }
    runIn(settings.updateInterval, getStatusLoop)
}


//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Variable and StateVar Routines

def parseQueryVariables(resp) {
	def nodes = getVariables()
    
    log.debug "parseResponse() ${resp.description}"
    
    def xml = new XmlParser().parseText(resp.body)
    log.debug "xml:[${xml}]"
    //def xmlVariables = xml.node
    //def printed = 0
    //log.debug "xmlVariables:[${xmlVariables}]"
    // xmlVariables.each { xml ->
    //     if (!(nodes[xml.address.text()])) {
    //         def node = [:]
    //         node.address = xml.address.text()
    //         node.name = xml.name.text()
    //         node.type = xml.type.text()
    //         node.deviceNetworkId = "ISY:${node.address}"
    //         node.status = 0
    //         xml?.property.each { prop ->
    //             if (prop.@id.equals('ST')) {
    //                 node.status = prop.@value
    //             }
    //         }
    //
    //         state.nodes[node.address] = node
    //
    //         if ((printed % 50) == 0) {
    //             log.debug "adding node:[${node}]"
    //         }
    //         printed += 1
    //     }
    // }
}

def queryVariables() {
    def isy = getIsyHub()
    if (!isy) { return }
    def host = isy.value.networkAddress + ":" + isy.value.port
    def auth = getAuthorization()
        
    log.debug "attempting to get nodes from ${host}"
    
    sendHubCommand(new physicalgraph.device.HubAction(
        	'method': 'GET',
        	'path': '/rest/vars',
        	'headers': [
        	    'HOST': host,
        	    'Authorization': auth
        	], null, [callback:parseQueryVariables]))
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Child Device Interfaces

// restGet() Create a new HubAction() as a GET request for a given device to the ISY hub to be
//			 used by the child device command routines.
//	device - The child device instance
//	path - The path to post the GET request to
//	args - Any additional options as supported by HubAction() such as a callback routine
def restGet(device, path, args=[:]) {
    def isy = getIsyHub()
    if (!isy) { 
    	log.warning "restGet() - unable to perform GET request. No ISY hub found"
        return
    }
    def host = getHost()
    def auth = getAuthorization()
    
    log.debug "restGet() ${host}${path}"
    
    new physicalgraph.device.HubAction(
        	'method': 'GET',
        	'path': path,
        	'headers': [
        	    'HOST': host,
        	    'Authorization': auth
        	], device.deviceNetworkId, args)
}


//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Helper funtions
def getHost() {
	def isy = getIsyHub()
    if (!isy) { return }
    def host = isy.value.networkAddress + ":" + isy.value.port
}
def getAuthorization() {
    def userpassascii = settings.username + ":" + settings.password
    "Basic " + userpassascii.encodeAsBase64().toString()
}
private Integer convertHexToInt(hex) {
    Integer.parseInt(hex,16)
}
private String convertHexToIP(hex) {
    [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}
def getDevices() {
    if (!state.devices) { state.devices = [:] }
    state.devices
}
def getNodes() {
    if (!state.nodes) { state.nodes = [:] }
    state.nodes
}
def getDebug() {
	settings.debug
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Mode Handling routines

def modeChangeHandler(evt) {
	log.debug "modeChangeHandler() mode changed ${evt.value}"
	
	if (settings.containsKey("modeMap${evt.value}")) {
		log.debug "modeMap${evt.value}: [${settings.modeMap${evt.value}]"
	}
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// SmartApp routines

def installed() {
    // remove location subscription
    unsubscribe()
    unschedule()
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    log.debug('Initializing')

    def isyHub = getIsyHub()
    def nodes = getNodes()
    def nodeTypes = [
    	'1.32.68.0':	'ISY Dimmer',		// Dimmer Switch
    	'1.66.68.0':	'ISY Dimmer',		// Dimmer Switch
        '1.14.67.0':	'ISY Dimmer',		// Dimmer Plugin Module
        '16.8.68.0':	'ISY Leak Sensor',	// Leak Sensor
        '2.55.72.0':	'ISY Switch',		// Plugin On/Off Module
        '5.11.16.0':	'ISY Thermostat',	// Thermostat
        '2.42.68.0':	'ISY Switch',		// On/Off Switch
    ]

	if (!isyHub) { return }
    
    settings.selectedNodes.each { nodeAddr ->
        def node = nodes.find { it.value.name == nodeAddr }
        def d = getAllChildDevices()?.find { it.device.deviceNetworkId == node.value.deviceNetworkId }
        if (!d) {
            if (nodeTypes[node.value.type]) {
            	def data = [
                    	"name": node.value.name,
                        "nodeAddress": node.value.address,
                        "deviceNetworkId": node.value.deviceNetworkId,
                        "status": node.value.status,
                        "onLevel": node.value?.onLevel,
                        "rampRate": node.value?.rampRate,
                    ]
            	log.debug("Adding node [${node.value.name}] to [${isyHub.value.hub}] as [${node.value.deviceNetworkId}]: ${data}")
                d = addChildDevice("raven42", nodeTypes[node.value.type], node.value.deviceNetworkId, isyHub?.value.hub, [
                    "label": node.value.name,
                    "data": data
                ])
                d.update()
           } else {
            	log.warning "Unknown device type ${node}"
           }
        }
    }
    
    runIn(30, getStatusLoop)
	subscribe(location, "mode", modeChangeHandler)
}
