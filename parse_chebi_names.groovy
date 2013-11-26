// ftp://ftp.ebi.ac.uk:21/pub/databases/chebi/Flat_file_tab_delimited/names_3star.tsv

def allowedSources = ['Chemical Ontology', 'IUPAC', 'ChEBI']
def availableSources = []
def sourceLabelTypes = []
def compoundIds = []
def compounds = [:]

new File('names_3star.tsv').eachLine { l ->

	def lParts = l.split("\t") // seperate the columns

	try {
		// make nice variables for the columns
		def compoundId 	= (lParts[1] as Integer) ?: null
		def labelType 	= lParts[2] ?: ''
		def source 		= lParts[3] ?: ''
		def label 		= lParts[4] ?: ''
		def adapted 	= lParts[5] ?: ''
		def language 	= lParts[6] ?: ''

		if (compoundId){ //} && source in allowedSources){

			// track the compoundIds and sources
			compoundIds << compoundId
			availableSources << source
			sourceLabelTypes << "${source}_${labelType}"

			if (!compounds[compoundId]){ compounds[compoundId] = [:] }
			if (!compounds[compoundId][source]){ compounds[compoundId][source] = [:] }
			if (!compounds[compoundId][source][labelType]){ compounds[compoundId][source][labelType] = [] }

			// save compound/source specific entry
			compounds[compoundId][source][labelType] << [
				'label': label,
				'language': language,
				'adapted': adapted
			]
		}
	} catch (Exception e) {
		// skipping header and other invalid lines in the file
	}
}

// make the list of availableSources unique()
availableSources = availableSources.unique().sort()
println "Found ${availableSources.size()} sources"//: ${availableSources}"

// make the list of sourceLabelTypes unique()
sourcesourceLabelTypesLabels = sourceLabelTypes.unique().sort()
println "Found ${sourceLabelTypes.size()} source labels: ${sourceLabelTypes.collect { it.replace('_',': ') }.join("\n") }"

// make the list of compoundIds unique()
compoundIds = compoundIds.unique().sort()
println "Found ${compoundIds.size()} compound identifiers"//: ${compoundIds}"


def tabFileText = ""
def tabFileHeader = []
	tabFileHeader << 'CompoundId'
	tabFileHeader << 'Chemical Ontology names'
	tabFileHeader << 'ChEBI brand names'
	tabFileHeader << 'ChEBI iupac names'
	tabFileHeader << 'IUPAC names'

if (1 == 2){
	compoundIds.each { compoundId ->

			def compound = compounds[compoundId]

			// get the Chemical Ontology name(s)
			def chemOntoNames = compound?."Chemical Ontology"?."NAME".collect { it.label }.join(', ') ?: ''

			// get the ChEBI Brand name(s)
			def chebiBrandNames = compound?."ChEBI"?."BRAND NAME".collect { it.label }.join(', ') ?: ''

			// get the ChEBI IUPAC name(s)
			def chebiIupacNames = compound?."ChEBI"?."IUPAC NAME".collect { it.label }.join(', ') ?: ''

			// get the IUPAC name(s)
			def iupacNames = compound?."IUPAC"?."IUPAC NAME".collect { it.label }.join(', ') ?: ''

			// make a nice tsv line out of it
			tabFileText += "${compoundId}\t${chemOntoNames}\t${chebiBrandNames}\t${chebiIupacNames}\t${iupacNames}\n"

	}
}

def filename = 'export.tsv'
new File(filename).delete()
new File(filename) << "${tabFileHeader.join("\t")}\n${tabFileText}"

