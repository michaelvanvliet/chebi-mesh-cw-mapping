import groovy.json.JsonSlurper

// location of the obo file
def locationOboFile = 'chebi.obo'

// where to write the output to
def outputFile = new File('export.tsv')
outputFile.delete() // make sure we create a new one

// prepare cache directory
new File('.cache/').mkdirs()

def chebiId = ''
def lineLimit = 0
def lineCount = 0

new File(locationOboFile).eachLine { oboLine ->

	lineCount++ // start counting lines!!!

	if (lineLimit == 0 || lineCount < lineLimit){

		if (oboLine.size() > 5 && oboLine[0..3] == 'id: '){
			chebiId	= oboLine[4..-1]
		}

		if (oboLine.size() > 5 && oboLine[0..5] == 'name: '){

			//options for the chebi
			def chebiOptions = []

			def chebiName = oboLine[6..-1]

			def meshPageHTML = getUrlContents('http://www.nlm.nih.gov/cgi/mesh/2013/MB_cgi?mode=&term=' + java.net.URLEncoder.encode(chebiName)) ?: ''

			if (meshPageHTML.contains('Please select a term from list')){
				def optionalMeshHits = getMultipleMeshResults(meshPageHTML)
				if (optionalMeshHits.size() <= 10){ // to many hits is probably because it is to general!
					optionalMeshHits.each { optionalMeshPage ->
						meshPageHTML = getUrlContents(optionalMeshPage)
						def meshDetails = getMeshDetailsFromHtml(meshPageHTML)
						chebiOptions <<  "${chebiId}\t${chebiName}\t${meshDetails['meshId']}\t${meshDetails['meshName']}\t${meshDetails['uuidFromMeshWithIdAndName']}\t${meshDetails['uuidFromMeshWithName']}"
					}
				} else {
					chebiOptions <<  "${chebiId}\t${chebiName}\t\t\t"
				}
			} else {
				def meshDetails = getMeshDetailsFromHtml(meshPageHTML)
				chebiOptions <<  "${chebiId}\t${chebiName}\t${meshDetails['meshId']}\t${meshDetails['meshName']}\t${meshDetails['uuidFromMeshWithIdAndName']}\t${meshDetails['uuidFromMeshWithName']}"
			}

			chebiOptions.unique().each {
				outputFile << "${lineCount}\t${it}\n"
			}
		}
	}
}

def getUrlContents(String url){

	// cache it
	def filenameFromUrl = url.replace('http://conceptwiki.nbiceng.net/web-ws/concept/search?q=', 'cw_')
	filenameFromUrl = filenameFromUrl.replace('http://www.nlm.nih.gov/cgi/mesh/2013/MB_cgi?mode=&term=', 'nlm_')
	def cacheFile

	try {
		cacheFile = new File('.cache/' + url.split('/')[2] + '_' + filenameFromUrl.bytes.encodeBase64().toString())

		// return cache html
		if (cacheFile.exists()){

			println "from cache ${url} ... "
			return cacheFile.text
		}
	} catch(e) {
		// sometimes the name is too long for caching, then we ignore it and keep fetching it from the internet
		println "${filenameFromUrl} is too long to cache."
	}

	// cache it for next runs
	def urlContents
	try {
		urlContents = new URL(url)?.text ?: ''
		println "downloading ${url} ... "
	} catch (Exception e) {
		println "Was unable to download content: ${e}"
	}

	try {
		cacheFile << urlContents
	} catch(e){
		println "Was unable to cache it: ${e}"
	}

	return urlContents

}

def getMeshDetailsFromHtml(String html){

	def meshDetails = [:]
	meshDetails['meshId'] = getMeshId(html) ?: ''
	meshDetails['meshName'] = getMeshName(html) ?: ''
	meshDetails['uuidFromMeshWithIdAndName'] = getCWuuid(meshDetails['meshId'] + ' ' + meshDetails['meshName']) ?: ''
	meshDetails['uuidFromMeshWithName'] = getCWuuid(meshDetails['meshName']) ?: ''
	return meshDetails
}

def getMeshId(String html){

	def meshId = ''

	def htmlParts1 = html.split('<TR><TH align=left>Unique ID</TH><TD colspan=1>') ?: []
	if (htmlParts1.size() >= 2){
		def htmlParts2 = htmlParts1[1].split('</TD></TR>') ?: []
		if (htmlParts2.size() >= 1){
			meshId = htmlParts2[0]
		}
	}

	return meshId
}

def getMultipleMeshResults(String html){

	def meshResultPages = []

	def htmlParts1 = html.split('MB_cgi') ?: []
	htmlParts1.each { chunk ->
		def htmlParts2 = chunk.split('field')
		if (htmlParts2.size() >= 2){
			meshResultPages << 'http://www.nlm.nih.gov/cgi/mesh/2013/MB_cgi?mode=&index=' + htmlParts2[0].replace('?mode=&index=','').replace('&','') + '&field=all&HM=&II=&PA=&form=&input='
		}
	}

	return meshResultPages
}

def getMeshName(String html){

	def meshName = ''
	def htmlParts1
	def htmlParts2

	htmlParts1 = html.split('<TR><TH align=left>MeSH Heading</TH><TD colspan=1>') ?: []
	if (htmlParts1.size() >= 2){
		htmlParts2 = htmlParts1[1].split('</TD></TR>') ?: []
		if (htmlParts2.size() >= 1){
			meshName = htmlParts2[0]
		}
	}

	if (meshName == ''){
		htmlParts1 = html.split('<TR><TH align=left>Name of Substance</TH><TD colspan=1>') ?: []
		if (htmlParts1.size() >= 2){
			htmlParts2 = htmlParts1[1].split('</TD></TR>') ?: []
			if (htmlParts2.size() >= 1){
				meshName = htmlParts2[0]
			}
		}
	}

	return meshName
}

def getCWuuid(String query){

	def uuid = ''
	def cwResponse

	query = query.trim()

	if (query.size() >= 3){
		try {
			def url = 'http://conceptwiki.nbiceng.net/web-ws/concept/search?q=' + java.net.URLEncoder.encode(query) + '&limit=1'
			cwResponse = getUrlContents(url)

			def JsonSlurper = new JsonSlurper()
			def cwConcept = JsonSlurper.parseText(cwResponse)
			//println cwConcept
			uuid = cwConcept.uuid[0] ?: ''
		} catch (Exception e) {
			// do nothing
		}
	}

	return uuid
}
