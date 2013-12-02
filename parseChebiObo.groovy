import groovy.json.JsonSlurper

// location of the obo file
def locationOboFile = 'chebi.obo'

// where to write the output to
def outputFile = new File('export.tsv')
outputFile.delete() // make sure we create a new one

// prepare cache directory
new File('.cache/').mkdirs()

def chebiId = ''
def chemspiderId = ''
def cwUuidFromChemspideId = ''
def cheb2chem = [:]
def lineLimit = 0
def lineCount = 0

def opsMapping = [:]
// read in OPS 2 Chemspider
new File('./private/LINKSET_EXACT_OPS_CHEMSPIDER_CHEBI20131111.ttl').eachLine { opschem ->
	try {
		def opschemParts = opschem.split(' ')
		def opsId = opschemParts[0].replace('ops:', '')
		def chemId = opschemParts[2].replace('<http://rdf.chemspider.com/', '').replace('>','')

		opsMapping[opsId] = ['chemId': chemId]
	} catch (e) {
		// ignore incorrect formatted lines!
	}
}

// read in Chebi 2 OPS mapping
new File('./private/LINKSET_EXACT_CHEBI20131111.ttl').eachLine { opscheb ->
	try {
		def opschebParts = opscheb.split(' ')
		def opsId = opschebParts[0].replace('ops:', '')
		def chebId = "CHEBI:" + opschebParts[2].replace('<http://purl.obolibrary.org/obo/CHEBI_', '').replace('>','')

		opsMapping[opsId] = opsMapping[opsId] + ['chebId': chebId]
	} catch (e) {
		// ignore incorrect formatted lines!
	}
}

// build chebi to chemspider mapping
opsMapping.each { opsId, m -> cheb2chem[m.chebId] = m.chemId}



new File(locationOboFile).eachLine { oboLine ->

	lineCount++ // start counting lines!!!

	if (lineLimit == 0 || lineCount < lineLimit){

		if (oboLine.size() > 5 && oboLine[0..3] == 'id: '){
			chebiId = oboLine[4..-1]
			chemspiderId = cheb2chem[chebiId] ?: ''
			cwUuidFromChemspideId = getCWuuidFromChemspideId(chemspiderId) ?: ''
		}

		if (oboLine.size() > 5 && oboLine[0..5] == 'name: '){

			//options for the chebi
			def chebiOptions = []

			def chebiName = oboLine[6..-1]

			def meshPageHTML = getUrlContents('http://www.nlm.nih.gov/cgi/mesh/2013/MB_cgi?mode=&term=' + java.net.URLEncoder.encode(chebiName)) ?: ''

			if (meshPageHTML.contains('No term found')){
				chebiOptions <<  "${chebiId}\t${chebiName}\t${chemspiderId}\t.\t.\t${cwUuidFromChemspideId}\t.\t.\tEOL"
			} else {
				if (meshPageHTML.contains('Please select a term from list')){
					def optionalMeshHits = getMultipleMeshResults(meshPageHTML)
					if (optionalMeshHits.size() <= 10){ // to many hits is probably because it is to general!
						optionalMeshHits.each { optionalMeshPage ->
							meshPageHTML = getUrlContents(optionalMeshPage)
							def meshDetails = getMeshDetailsFromHtml(meshPageHTML)
							chebiOptions <<  "${chebiId}\t${chebiName}\t${chemspiderId}\t${meshDetails['meshId']}\t${meshDetails['meshName']}\t${cwUuidFromChemspideId}\t${meshDetails['uuidFromMeshWithIdAndName']}\t${meshDetails['uuidFromMeshWithName']}\tEOL"
						}
					} else {
						chebiOptions <<  "${chebiId}\t${chebiName}\t${chemspiderId}\t.\t.\t${cwUuidFromChemspideId}\t.\t.\tEOL"
					}
				} else {
					def meshDetails = getMeshDetailsFromHtml(meshPageHTML)
					chebiOptions <<  "${chebiId}\t${chebiName}\t${chemspiderId}\t${meshDetails['meshId']}\t${meshDetails['meshName']}\t${cwUuidFromChemspideId}\t${meshDetails['uuidFromMeshWithIdAndName']}\t${meshDetails['uuidFromMeshWithName']}\tEOL"
				}
			}

			chebiOptions.unique().each {

				// how do the UUIDs relate?
				def lineScore = 9
				def noOfFoundUUIDs = 0
				def foundUUIDs = []
				try {
					if ("${it.split("\t")[-2]}".size() == 36) {
						foundUUIDs << it.split("\t")[-2]
						noOfFoundUUIDs++
					// } else {
					// 	foundUUIDs << 'CHEMUUID'
					}

					if ("${it.split("\t")[-3]}".size() == 36) {
						foundUUIDs << it.split("\t")[-3]
						noOfFoundUUIDs++
					// } else {
					// 	foundUUIDs << 'MESHIDNUUID'
					}

					if ("${it.split("\t")[-4]}".size() == 36) {
						foundUUIDs << it.split("\t")[-4]
						noOfFoundUUIDs++
					// } else {
					// 	foundUUIDs << 'MESHNUUID'
					}

					lineScore = foundUUIDs.unique().size()
				} catch (e) {
					// not all could be found, score is 0
				}

				// does Chemspider agree with (one of the) Mesh UUIDs
				def chemspiderMeshHit = 0
				if (
					(lineScore <= 2) &&
					(it.split("\t")[-4] != '') && (
						(it.split("\t")[-4] == it.split("\t")[-3]) ||
						(it.split("\t")[-4] == it.split("\t")[-2])
					)
				    ) {
					chemspiderMeshHit = 1
					//println "HIT: ${it.split("\t")[-2]} >> ${it.split("\t")[-3]} OR ${it.split("\t")[-4]}"
				}

				// calc overall score
				def overallScore = 0
				if (noOfFoundUUIDs >= 1) {
					overallScore = ((noOfFoundUUIDs)/(lineScore+1))+chemspiderMeshHit
					overallScore = Math.round(overallScore*10)
				}

				outputFile << "${lineCount}\t${overallScore}\t${noOfFoundUUIDs}\t${lineScore}\t${chemspiderMeshHit}\t${it}\n"
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

			//println "from cache ${url} ... "
			//return cacheFile.text
		}
	} catch(e) {
		// sometimes the name is too long for caching, then we ignore it and keep fetching it from the internet
		println "${filenameFromUrl} is too long to cache."
	}

	// cache it for next runs
	def urlContents
	try {
		urlContents = new URL(url)?.text ?: ''
		//println "downloading ${url} ... "
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

def getCWuuidFromChemspideId(String chempiderId){
	def uuid = ''
	def cwResponse

	query = chempiderId.trim()

	if (query.size() >= 3){
		try {
			def url = 'http://conceptwiki.nbiceng.net/web-ws/concept/search?q=' + java.net.URLEncoder.encode(query) + '&branch=4&limit=1'
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
