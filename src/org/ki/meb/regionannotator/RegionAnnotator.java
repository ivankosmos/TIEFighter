package org.ki.meb.regionannotator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.ConfigurationNode;
import org.apache.ibatis.jdbc.SQL;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jakz.common.JSONObject;
import org.jakz.common.ApplicationException;
import org.jakz.common.DataCache;
import org.jakz.common.IndexedMap;
import org.jakz.common.Util;
import org.jakz.common.DataEntry;
import org.jakz.common.formatter.CustomFormatter;
import org.jakz.common.formatter.CustomFormatter.IOType;


public class RegionAnnotator
{
	
	public static final String version = "1.7.1";
	
	private static String clHelp = TextMap.help;
	private static String clInputFileFolder = TextMap.input;
	private static String clOutputFileFolder = TextMap.output;
	private static String clReference = "reference";
	private static String clGene = "gene";
	private static String clNonames = "nonames";
	private static String clGet = "get";
	private static String clGetall = "getall";
	private static String clInputFormat = "iformat";
	private static String clOutputFormat = "oformat";
	private static String clOverwrite = "overwrite";
	private static String clOperate= "operate";
	private static String clTimeout = "timeout";
	private static String clDatabaseLocation = "db";
	private static String clConfigFile = "config";
	private static String clTemplate = "template";
	
	private static String confInputfolderpath = clInputFileFolder;
	private static String confOutputfolderpath = clOutputFileFolder;
	private static String confTempfolderpath = "temp";
	private static String confDatabaseCacheSizeKb = "dbcachesizekb";
	
	
	
	private CommandLine commandLine;
	private long startTimeNanos;
	
	private static Options clOptions = new Options();
	
	private File settingConfigFile, settingInputFileFolder, settingOutputFileFolder, settingDBFolder, settingTempFolder, settingDocumentationTemplate;
	private CustomFormatter.IOType settingInputFormat, settingOutputFormat;
	private boolean settingReference, settingGene, settingOverwriteExistingTables, settingFirstRowVariableNames;
	private Integer settingDBCacheSizeKB;
	private DataCache dataCache;
	private FilenameFilter filterExcelXlsx, filterCSV, filterTSV, filterJSON;
	private DataEntry referenceEntryTemplate, linkEntryTemplate, reducedLinkEntryTemplate;
	private IndexedMap<String, DataEntry> entryTemplate;
	private IndexedMap<String,XSSFCellStyle> excelStyle;
	
	static
	{
		//clOptions.addOption(OptionBuilder.create(TextMap.regtest)); //inactivated as default
		clOptions.addOption(clHelp,false,"Print usage help.");
		clOptions.addOption(Option.builder(clInputFileFolder).hasArg().argName("file/folder path").desc("Input from the specified file or folder.").build());
		clOptions.addOption(Option.builder(clOutputFileFolder).hasArg().argName("file/folder path").desc("Output to the specified file or folder.").build());
		clOptions.addOption(Option.builder(clTemplate).hasArg().argName("file path").desc("Documentation template for excel output.").build());
		clOptions.addOption(clReference,false,"Enter reference data.");
		clOptions.addOption(clGene,false,"Enter gene (reference) data.");
		clOptions.addOption(clNonames,false,"The first row of data in the input files contains NO column names.");
		clOptions.addOption(Option.builder(clGet).hasArg().argName("dataset name").desc("Get specific database content (table/view) as exported output.").build());
		clOptions.addOption(clGetall,false,"Output all database content.");
		
		clOptions.addOption(Option.builder(clOutputFormat).hasArg().argName("format - DATACACHE,EXCEL,CSV,TSV").desc("Force output format.").build());
		clOptions.addOption(Option.builder(clInputFormat).hasArg().argName("format - DATACACHE,EXCEL,CSV,TSV").desc("Force input format.").build());
		clOptions.addOption(Option.builder(clOverwrite).hasArg().argName("true/false").desc("Overwrite existing tables with the same names. Default - true.").build());
		clOptions.addOption(Option.builder(clOperate).hasArg().argName("true/false").desc("Perform operation specifics or not. Default - true.").build());
		clOptions.addOption(Option.builder(clTimeout).hasArg().argName("time limit in milliseconds").desc("Database connection timeout. Default 30000 milliseconds.").build());
		clOptions.addOption(Option.builder(clDatabaseLocation).hasArg().argName("folder path").desc("Database location.").build());
		clOptions.addOption(Option.builder(clConfigFile).hasArg().argName("file path").desc("Config file.").build());
	}

	public RegionAnnotator()
	{
		startTimeNanos= System.nanoTime();
		
		filterExcelXlsx= new FilenameFilter() 
		{
			@Override
			public boolean accept(File dir, String name) 
			{
				return name.toLowerCase().matches("^.+\\.xlsx$");
			}
		};
		
		filterCSV= new FilenameFilter() 
		{
			@Override
			public boolean accept(File dir, String name) 
			{
				return name.toLowerCase().matches("^.+\\.csv$");
			}
		};
		
		filterTSV= new FilenameFilter() 
		{
			@Override
			public boolean accept(File dir, String name) 
			{
				return name.toLowerCase().matches("^.+\\.tsv$");
			}
		};
		
		filterJSON= new FilenameFilter() 
		{
			@Override
			public boolean accept(File dir, String name) 
			{
				return name.toLowerCase().matches("^.+\\.json$");
			}
		};
	}
	
	private void printTimeMeasure()
	{
		System.out.println("Running time: "+(System.nanoTime()-startTimeNanos)/1E9+" seconds");
	}

	private void init() throws ConfigurationException, ApplicationException
	{
		if(settingConfigFile.exists())
		{
			System.out.println("Using file config from "+settingConfigFile);
			XMLConfiguration config = new XMLConfiguration(settingConfigFile);
			ConfigurationNode rootNode = config.getRootNode();
			if(!rootNode.getChildren(confInputfolderpath).isEmpty())
				settingInputFileFolder = new File((String)((ConfigurationNode)rootNode.getChildren(confInputfolderpath).get(0)).getValue()).getAbsoluteFile();
			if(!rootNode.getChildren(confOutputfolderpath).isEmpty())
				settingOutputFileFolder = new File((String)((ConfigurationNode)rootNode.getChildren(confOutputfolderpath).get(0)).getValue()).getAbsoluteFile();
			if(!rootNode.getChildren(confTempfolderpath).isEmpty())
				settingTempFolder = new File((String)((ConfigurationNode)rootNode.getChildren(confTempfolderpath).get(0)).getValue()).getAbsoluteFile();
			if(!rootNode.getChildren(confDatabaseCacheSizeKb).isEmpty())
				settingDBCacheSizeKB = Integer.parseInt((String)((ConfigurationNode)rootNode.getChildren(confDatabaseCacheSizeKb).get(0)).getValue());
		}
		
		if(settingInputFileFolder==null)
			settingInputFileFolder=new File("input").getAbsoluteFile();
		if(settingOutputFileFolder==null)
			settingOutputFileFolder=new File("output").getAbsoluteFile();
		if(settingTempFolder==null)
			settingTempFolder=settingOutputFileFolder;
		
		settingInputFormat=null;
		settingOutputFormat=IOType.EXCEL;
		settingReference=false;
		settingGene=false;
		settingFirstRowVariableNames=true;
		
		
		settingDocumentationTemplate = new File("documentation.xlsx"); //default
		if(commandLine.hasOption(clTemplate))
		{
			settingDocumentationTemplate=new File(commandLine.getOptionValue(clTemplate));
		}
		
		if(commandLine.hasOption(clInputFileFolder))
		{
			settingInputFileFolder=new File(commandLine.getOptionValue(clInputFileFolder));
			
			//force output settings
			if(settingInputFileFolder.isDirectory())
				settingOutputFileFolder=settingInputFileFolder;
			else
			{
				File newOutputFolder = settingInputFileFolder.getAbsoluteFile().getParentFile();
				if(newOutputFolder!=null&&newOutputFolder.isDirectory())
					settingOutputFileFolder=newOutputFolder;
			}
		}
		
		if(commandLine.hasOption(clOutputFileFolder))
		{
			settingOutputFileFolder=new File(commandLine.getOptionValue(clOutputFileFolder));
		}
		
		
		if(commandLine.hasOption(clInputFormat))
		{
			String ov = commandLine.getOptionValue(clInputFormat).trim().toUpperCase();
			try
			{
				settingInputFormat=IOType.valueOf(ov);
			}
			catch (Exception e)
			{
				throw new ApplicationException("Input format error. Provided ["+ov+"]",e);
			}
		}
		
		if(commandLine.hasOption(clOutputFormat))
		{
			String ov = commandLine.getOptionValue(clOutputFormat).trim().toUpperCase();
			try
			{
				settingOutputFormat=IOType.valueOf(ov);
			}
			catch (Exception e)
			{
				throw new ApplicationException("Output format error. Provided ["+ov+"]",e);
			}
		}
		
		
		if(settingDBCacheSizeKB==null)
			settingDBCacheSizeKB=2000000;
		
		//settingDBFolder = new File(Paths.get(".").toAbsolutePath().normalize().toString()); //not for older java
		settingDBFolder = new File("");
		
		
		
		if(commandLine.hasOption(clDatabaseLocation))
		{
			settingDBFolder=new File(commandLine.getOptionValue(clDatabaseLocation));
		}
		
		//dataCache=new DataCache("./RegionAnnotator");
		String path = settingDBFolder.getAbsolutePath()+File.separator+"RegionAnnotator";
		dataCache=new DataCache(path);
		
		
		
		if(commandLine.hasOption(clReference))
		{
			settingReference=true;
		}
		
		if(commandLine.hasOption(clGene))
		{
			settingGene=true;
		}
		
		if(commandLine.hasOption(clNonames))
		{
			settingFirstRowVariableNames=false;
		}
		
		settingOverwriteExistingTables=true;
		if(commandLine.hasOption(clOverwrite))
		{
			settingOverwriteExistingTables=Boolean.parseBoolean(commandLine.getOptionValue(clOverwrite));
		}
		
		if(commandLine.hasOption(clTimeout))
		{
			dataCache.setConnectionTimeoutMilliseconds(Long.parseLong(commandLine.getOptionValue(clTimeout)));
		}
		
		//tempfiles - for poi
		//redirect temp to output
		if(settingOutputFileFolder.isDirectory())
			settingTempFolder=settingOutputFileFolder;
		else
		{
			File newTempFolder = settingOutputFileFolder.getAbsoluteFile().getParentFile();
			if(newTempFolder!=null&&newTempFolder.isDirectory())
				settingTempFolder=newTempFolder;
		}
		//else fallback on standard config
		
		System.out.println("Tempfolder was: "+System.getProperty("java.io.tmpdir"));
		System.setProperty("java.io.tmpdir", settingTempFolder.getAbsolutePath());
		System.out.println("Tempfolder is now set to: "+System.getProperty("java.io.tmpdir"));
		
		
		//formalized entry templates, names in UPPER CASE!!!!
		DataEntry ne; JSONObject element;
		entryTemplate=new IndexedMap<String, DataEntry>();
				
		ne=dataCache.newEntry("_USER_INPUT"); //WORK
		ne.local=true;
		//ne.temporary=true;
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("INPUTID",element);

		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("CHR",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP1",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP2",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("GENENAME",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("SNPID",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.DOUBLE);
		ne.namemap.put("PVALUE",element);
		
		entryTemplate.put("_USER_INPUT", ne);
		
		
		ne=dataCache.newEntry("USER_INPUT"); //WORK
		ne.local=true;
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("INPUTID",element);

		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("CHR",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP1",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP2",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("GENENAME",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("SNPID",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.DOUBLE);
		ne.namemap.put("PVALUE",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		element.put("isExcelFormula", true);
		element.put("isHyperlink", true);
		ne.namemap.put("UCSC_LINK",element);
		
		entryTemplate.put("USER_INPUT", ne);
		
				
		ne=dataCache.newEntry("GENE_MASTER");

		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("CHR",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP1",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP2",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("GENENAME",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("ENTREZ",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("ENSEMBL",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("TTYPE",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("STRAND",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("PRODUCT",element);
		
		entryTemplate.put("GENE_MASTER", ne);
		
		
		ne=dataCache.newEntry((String)null);	//reference data
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("CHR",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP1",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP2",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("GENENAME",element);
		
		referenceEntryTemplate=ne;
		
		
		ne=dataCache.newEntry((String)null);	//link data
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("INPUTID",element);

		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("CHR",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP1",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		ne.namemap.put("BP2",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("GENENAME",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		ne.namemap.put("SNPID",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.DOUBLE);
		ne.namemap.put("PVALUE",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		element.put("isExcelFormula", true);
		element.put("isHyperlink", true);
		ne.namemap.put("UCSC_LINK",element);
		
		linkEntryTemplate=ne;
		
		
		ne=linkEntryTemplate.copy();		//reduced link data
		
		element=new JSONObject();
		element.put("type", java.sql.Types.INTEGER);
		element.put("hide", true);
		ne.namemap.put("ENTREZ_GM",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		element.put("hide", true);
		ne.namemap.put("ENSEMBL_GM",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		element.put("hide", true);
		ne.namemap.put("TTYPE_GM",element);
		
		element=new JSONObject();
		element.put("type", java.sql.Types.VARCHAR);
		element.put("hide", true);
		ne.namemap.put("STRAND_GM",element);
		
		reducedLinkEntryTemplate = ne;
		
		excelStyle = new IndexedMap<String, XSSFCellStyle>();
	}
	
	public static CommandLine constructCommandLine(String[] args) throws ParseException
	{
		CommandLine commandLine;
		CommandLineParser parser = new org.apache.commons.cli.GnuParser();
		try
		{
			commandLine = parser.parse(clOptions, args);
		}
		catch (Exception e)
		{
			commandLine = parser.parse(clOptions, new String[]{"-"+clHelp});
		}
		return commandLine;
	}
	
	
	public static void main(String[] args) throws Exception
	{
		System.out.println("//¤//RegionAnnotator//¤//		version "+version);
		new RegionAnnotator().setCommandLine(constructCommandLine(args)).runCommands();
	}
	
	
	private RegionAnnotator runCommands() throws Exception
	{
		if(commandLine.hasOption(TextMap.help) || commandLine.getOptions().length==0
				//||(!commandLine.hasOption(TextMap.config)&&!commandLine.hasOption("reference")&&!commandLine.hasOption(TextMap.operate)&&!commandLine.hasOption(TextMap.get)&&!commandLine.hasOption("getall"))
				)
		{
			printHelp();
			return this;
		}
		
		if(commandLine.hasOption(clConfigFile))
		{
			settingConfigFile = new File(commandLine.getOptionValue(clConfigFile));
		}
		else
		{
			settingConfigFile = new File("config.xml");
		}
		
		init();
		
		System.out.println("Waiting for database connection...");
		dataCache.createCacheConnectionEmbedded();
		System.out.println("Database connected");
		dataCache.setDBCacheSizeKB(settingDBCacheSizeKB);
		dataCache.commit();
		
		
		inputDataFromFiles();
		operate();
		outputDataToFiles();
		
		dataCache.shutdownCacheConnection();
		
		System.out.println("THE END");
		return this;
	}
	
	
	//always to standard output
	private void printHelp()
	{
		System.out.println("Gene Connector Command Line Application");
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("java -jar \"RegionAnnotator.jar\"", "", clOptions, "", true);
	}
	
	public RegionAnnotator setCommandLine(CommandLine nCommandLine)
	{
		commandLine=nCommandLine;
		return this;
	}
	
	
	
	
	private void inputDataFromFiles() throws ApplicationException, Exception
	{
		
		if(!commandLine.hasOption(clInputFileFolder))
			return;
			
		CustomFormatter inputReader = new CustomFormatter().setDataCache(dataCache).setOverwriteExistingTables(settingOverwriteExistingTables).setFirstRowVariableNames(settingFirstRowVariableNames);
		
		DataEntry currentEntryTemplate;
		
		
		if(settingGene)
			currentEntryTemplate=entryTemplate.getValue("GENE_MASTER");
		else if(settingReference)
			currentEntryTemplate=referenceEntryTemplate;
		else
			currentEntryTemplate=entryTemplate.getValue("_USER_INPUT"); //for standard input
		
		inputReader.setPath(currentEntryTemplate.path);
		
		if(settingInputFileFolder.isFile())
		{
			 inputDataFromFile(settingInputFileFolder, settingInputFormat, inputReader, currentEntryTemplate);
		}
		else if(settingInputFileFolder.isDirectory())
		{
			
			//import all files in input
			if(settingInputFormat==null)
			{
				File[] inputFilesJSON = settingInputFileFolder.listFiles(filterJSON);
				for(int iFile=0; iFile<inputFilesJSON.length; iFile++)
				{
					inputDataFromFile(inputFilesJSON[iFile], IOType.DATACACHE, inputReader, currentEntryTemplate);
				}
				
				File[] inputFilesCsv = settingInputFileFolder.listFiles(filterCSV);
				for(int iFile=0; iFile<inputFilesCsv.length; iFile++)
				{
					inputDataFromFile(inputFilesCsv[iFile], IOType.CSV, inputReader, currentEntryTemplate);
				}
				
				File[] inputFilesTsv = settingInputFileFolder.listFiles(filterTSV);
				for(int iFile=0; iFile<inputFilesTsv.length; iFile++)
				{
					inputDataFromFile(inputFilesTsv[iFile], IOType.TSV, inputReader, currentEntryTemplate);
				}
				
				File[] inputFilesXlsx = settingInputFileFolder.listFiles(filterExcelXlsx);
				for(int iFile=0; iFile<inputFilesXlsx.length; iFile++)
				{
					inputDataFromFile(inputFilesXlsx[iFile], IOType.EXCEL, inputReader, currentEntryTemplate);
				}
			}
			else
			{
				File[] inputFiles = settingInputFileFolder.listFiles();
				for(int iFile=0; iFile<inputFiles.length; iFile++)
				{
					try 
					{
						inputDataFromFile(inputFiles[iFile], settingInputFormat, inputReader, currentEntryTemplate);
					}
					catch (Exception e)
					{
						System.err.println("Failed to parse file "+inputFiles[iFile].getAbsolutePath()+".\nReason:\n"+Util.getStackTraceString(e));
					}
				}
			}
			
		}
		else throw new ApplicationException("Wrong type of input; it is not a file nor a directory.");
		
		dataCache.commit();
	}
	
	private void inputDataFromFile(File inputFile, IOType usedInputFormat, CustomFormatter inputReader, DataEntry currentEntryTemplate) throws InvalidFormatException, IOException, ApplicationException, SQLException
	{
		if(inputFile.isDirectory())
			throw new ApplicationException("Can't input data from directory.");
		
		if(usedInputFormat==null)
		{
			if(inputFile.getName().toLowerCase().matches("^.+\\.xlsx$"))
			{
				usedInputFormat=IOType.EXCEL;
			}
			else if(inputFile.getName().toLowerCase().matches("^.+\\.csv$"))
			{
				usedInputFormat=IOType.CSV;
			}
			else if(inputFile.getName().toLowerCase().matches("^.+\\.tsv$"))
			{
				usedInputFormat=IOType.TSV;
			}
			else if(inputFile.getName().toLowerCase().matches("^.+\\.json$"))
			{
				usedInputFormat=IOType.DATACACHE;
			}
		}
		
		DataEntry currentEntry = currentEntryTemplate.copy();
		if(settingGene)
		{
			inputReader.setInputType(usedInputFormat).setInputFile(inputFile).read(currentEntry);
			for(int i=0; i<currentEntryTemplate.namemap.size(); i++)
			{
				dataCache.index(currentEntry.path, currentEntryTemplate.namemap.getKeyAt(i));
			}

		}
		else if(settingReference)
		{
			//custom naming
			currentEntry.path=inputFile.getName();
			int dotIndex = currentEntry.path.lastIndexOf('.');
			if(dotIndex>=0)
				currentEntry.path="_"+currentEntry.path.substring(0,dotIndex).replace('.', '_');
			
			inputReader.setInputType(usedInputFormat).setInputFile(inputFile).read(currentEntry);
			for(int i=0; i<currentEntryTemplate.namemap.size(); i++)
			{
				dataCache.index(currentEntry.path, currentEntryTemplate.namemap.getKeyAt(i));
			}
		}
		else
		{
			//USER_INPUT settings
			currentEntryTemplate.memory=true;
			currentEntryTemplate.temporary=false;
			currentEntryTemplate.local=false;
			inputReader.setInputType(usedInputFormat).setInputFile(inputFile).read(currentEntry);
			for(int i=0; i<currentEntryTemplate.namemap.size(); i++)
			{
				dataCache.index(currentEntry.path, currentEntryTemplate.namemap.getKeyAt(i));
			}
		}
	}
	
	
	private void outputDataToFiles() throws InstantiationException, IllegalAccessException, ClassNotFoundException, InvalidFormatException, SQLException, ApplicationException, IOException
	{
		
		if(commandLine.hasOption(clGetall))
		{
			System.out.println("Outputting to files...");
			outputAllData();
			System.out.println("Outputted files done");
		}
		else if(commandLine.hasOption(clGet))
		{
			System.out.println("Outputting to file...");
			outputDataToFile(commandLine.getOptionValue(TextMap.get),null,false,entryTemplate.getValue(commandLine.getOptionValue(TextMap.get)),null);
			System.out.println("Outputted file done");
		}
		else if((!settingGene&&!settingReference) && (commandLine.hasOption(clOutputFileFolder)||commandLine.hasOption(clOutputFormat)||commandLine.hasOption(clInputFileFolder)))
		{
			//Outputting result data
			System.out.println("Outputting to file...");
			outputAllResultData();
			System.out.println("Outputted file done");
		}
		
		printTimeMeasure();
		
	}
	
	private void outputDataToFile(String datasetName, String filename, boolean appendToExcel, DataEntry currentEntryTemplate, File nof) throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException, ApplicationException, InvalidFormatException, IOException
	{

		CustomFormatter outputWriter = new CustomFormatter().setDataCache(dataCache).setInputType(IOType.DATACACHE).setExcelStyle(excelStyle);
		File of;
		if(filename==null)
		{
			if(datasetName==null)
				filename="output";
			else
				filename=datasetName+"_out";
		}
		
		if(nof!=null&&!nof.isDirectory())
		{
			of=nof;
		}
		else if(!settingOutputFileFolder.isDirectory())
		{
			of=settingOutputFileFolder;
		}
		else
		{
			String outputFolderPath="";
			if(settingOutputFileFolder.isDirectory())
				outputFolderPath= settingOutputFileFolder.getAbsolutePath()+File.separator;
			
			if(settingOutputFormat==IOType.CSV)
			{
				of=new File(outputFolderPath+filename+".csv");
			}
			else if(settingOutputFormat==IOType.TSV)
			{
				of=new File(outputFolderPath+filename+".tsv");
			}
			else if(settingOutputFormat==IOType.EXCEL)
			{
				of=new File(outputFolderPath+filename+".xlsx");
			}
			else
			{
				of=new File(outputFolderPath+filename+".json");
			}
		}
		
		
		outputWriter.setPath(datasetName).setOutputType(settingOutputFormat).setOutputFile(of).setExcelAppend(appendToExcel).setOutputSkipEmptyColumns(true);
		DataEntry currentEntry = null;
		if(currentEntryTemplate!=null)
			currentEntry = currentEntryTemplate.copy();
		outputWriter.write(currentEntry);
	}
	
	private void outputAllResultData() throws InstantiationException, IllegalAccessException, ClassNotFoundException, InvalidFormatException, SQLException, ApplicationException, IOException
	{
		
		//excel composite file as standard
		if(!commandLine.hasOption(clOutputFormat)||settingOutputFormat==IOType.EXCEL)
		{
			String filename = clOutputFileFolder+"_excel";
			settingOutputFormat=IOType.EXCEL;
		
			if(!commandLine.hasOption(clOutputFileFolder))
			{
				if(settingInputFileFolder!=null&&!settingInputFileFolder.isDirectory())
				{
					filename = settingInputFileFolder.getName();
					String outputFolderPath="";
					if(settingOutputFileFolder.isDirectory())
						outputFolderPath= settingOutputFileFolder.getAbsolutePath()+File.separator;
					if(filename.indexOf('.')>=0)
						filename = filename.substring(0,filename.lastIndexOf("."));
					
					settingOutputFileFolder = new File(outputFolderPath+filename+"_out.xlsx");
				}
			}
			
			
		}
		
		if(!settingOutputFileFolder.isDirectory())
			Util.deleteFileIfExistsOldCompatSafe(settingOutputFileFolder);
		
		
		//append documentation/READ ME
		if(settingDocumentationTemplate.exists() && !settingDocumentationTemplate.isDirectory())
		{
			System.out.println("Importing documentation...");
			XSSFWorkbook documentationExcelWorkbook = new XSSFWorkbook(settingDocumentationTemplate);
			XSSFWorkbook outputFileWorkbook = new XSSFWorkbook();
			XSSFSheet readMeSheetSource = documentationExcelWorkbook.getSheet("readme");
			XSSFSheet readMeSheetTarget  = outputFileWorkbook.createSheet("README");
			
			//Style copy
			for(int i=0; i<documentationExcelWorkbook.getNumCellStyles();i++)
			{
				String styleCallsign = "read_me"+i;
				XSSFCellStyle newCellStyle;
				newCellStyle = outputFileWorkbook.createCellStyle();
				newCellStyle.cloneStyleFrom(documentationExcelWorkbook.getCellStyleAt(i));
				excelStyle.put(styleCallsign,newCellStyle);
			}
			
			//Iterator<Row> iRow = readMeSheetSource.rowIterator();
			int irow =0;
			for (Row crow : readMeSheetSource)
			{
				XSSFRow trow = readMeSheetTarget.createRow(irow++);
				for(Cell ccell : crow)
				{
					
					XSSFCell tcell = trow.createCell(ccell.getColumnIndex());
					int type = ccell.getCellType();
					tcell.setCellType(ccell.getCellType());
					//tcell.setCellStyle(ccell.getCellStyle()); //not working
					int cindex = ccell.getCellStyle().getIndex();
					//System.out.println("Style index: "+cindex);
					
					XSSFCellStyle newCellStyle;
					newCellStyle=excelStyle.getValueAt(cindex);
					tcell.setCellStyle(newCellStyle);
					
					if(type==XSSFCell.CELL_TYPE_BLANK||type==XSSFCell.CELL_TYPE_ERROR)
					{
						//nothing
					}
					else if(type==Cell.CELL_TYPE_BOOLEAN || (type==Cell.CELL_TYPE_FORMULA && ccell.getCachedFormulaResultType()==Cell.CELL_TYPE_BOOLEAN))
					{
						tcell.setCellValue(ccell.getBooleanCellValue());
					}
					else if(type==Cell.CELL_TYPE_NUMERIC || (type==Cell.CELL_TYPE_FORMULA && ccell.getCachedFormulaResultType()==Cell.CELL_TYPE_NUMERIC))
					{
						if (DateUtil.isCellDateFormatted(ccell))
							tcell.setCellValue(ccell.getDateCellValue());
						else 
							tcell.setCellValue(ccell.getNumericCellValue());
					}
					else if(type==Cell.CELL_TYPE_STRING || (type==Cell.CELL_TYPE_FORMULA && ccell.getCachedFormulaResultType()==Cell.CELL_TYPE_STRING))
					{
						tcell.setCellValue(ccell.getRichStringCellValue());
					}
					
				}
			}
			
			//column sizes
			for(int i=0; i<10;i++)
			{
				readMeSheetTarget.autoSizeColumn(i);
			}
			//System.out.println("Num styles after read me import:"+outputFileWorkbook.getNumCellStyles());
			FileOutputStream fileOut = new FileOutputStream(settingOutputFileFolder);
			outputFileWorkbook.write(fileOut);
		    fileOut.close();
			//outputFileWorkbook.close(); //unclear if we need to close these
			//documentationExcelWorkbook.close();
			System.out.println("Documentation import done. Continuing with outputting files...");
		}
		
		//outputDataToFile("README",null,true, null, settingOutputFileFolder);
		outputDataToFile("user_input",null,true, entryTemplate.getValue("USER_INPUT"), settingOutputFileFolder);
		outputDataToFile("protein_coding_genes",null,true, linkEntryTemplate, settingOutputFileFolder);
		outputDataToFile("gwas_catalog",null,true, linkEntryTemplate, settingOutputFileFolder);
		outputDataToFile("omim",null,true, reducedLinkEntryTemplate, settingOutputFileFolder);
		outputDataToFile("psychiatric_cnvs",null,true, linkEntryTemplate, settingOutputFileFolder);
		outputDataToFile("asd_genes",null,true, reducedLinkEntryTemplate, settingOutputFileFolder);
		outputDataToFile("id_devdelay_genes",null,true, reducedLinkEntryTemplate, settingOutputFileFolder);
		outputDataToFile("mouse_knockout",null,true, linkEntryTemplate, settingOutputFileFolder);
		
	}
	
	private void outputAllData() throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException, InvalidFormatException, ApplicationException, IOException
	{
		ArrayList<String> datasets =dataCache.listDatasets();
		for(int i=0; i<datasets.size(); i++)
		{
			outputDataToFile(datasets.get(i),null,false,entryTemplate.getValue(datasets.get(i)),null);
		}
	}
	
	/*
	private void performTavernaWorkflow() throws ReaderException, IOException
	{
		WorkflowBundleIO io = new WorkflowBundleIO();
		WorkflowBundle ro = io.readBundle(new File("workflow.t2flow"), null);
		ro.getMainWorkflow();
	}
	*/
	
	
	
	
	private void operate() throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException, ApplicationException, IOException
	{
		if(settingGene||settingReference)
			return;
		
		if(!commandLine.hasOption(clInputFileFolder))
			return;
		
		printTimeMeasure();
		System.out.println("Operating...");
		long operationStartTimeNanos = System.nanoTime();
		
		final String schemaName = "PUBLIC";
		String q;
		
		
		
		
		
		
		//operations
		
		//documentation
		/*
		File documentationTxt = new File("documentation.txt");
		FileInputStream fis = new FileInputStream(documentationTxt);
		byte[] data = new byte[(int) documentationTxt.length()];
		fis.read(data);
		fis.close();
		//String[] documentation = new String(data, "UTF-8").split("(\n\n|\r\n\r\n)");
		String[] documentation = {new String(data, "UTF-8")};
		DataEntry documentationEntry = dataCache.newEntry("README");
		

		for(int i=0; i<documentation.length; i++)
		{
			JSONObject row = new JSONObject();
			JSONObject rowData = new JSONObject();
			JSONObject entry = new JSONObject();
			entry.put("type", java.sql.Types.VARCHAR);
			entry.put("value", documentation[i]);
			//documentationEntry.namemap.put("text",entry);
			rowData.put("text", entry);
			row.put("data", rowData);
			documentationEntry.rows.put(row);
		}
		if(dataCache.getHasTable("README"))
			dataCache.dropTable("README");
		dataCache.enter(documentationEntry).commit();
		*/
		
		dataCache.index("_USER_INPUT", "INPUTID");
		dataCache.index("_USER_INPUT", "CHR");
		dataCache.index("_USER_INPUT", "BP1");
		dataCache.index("_USER_INPUT", "BP2");
		dataCache.index("_USER_INPUT", "GENENAME");
		dataCache.index("_USER_INPUT", "SNPID");
		dataCache.index("_USER_INPUT", "PVALUE");
		
		
		
		final SQL userInput = new SQL()
		{
			{
				//SELECT("CHR"); //hg19chrc,	r0
				//SELECT("BP1"); //six1, 		r1
				//SELECT("BP2"); //six2, 		r2
				SELECT("_USER_INPUT.*"); //WORK
				SELECT("chr||':'||"+dataCache.scriptSeparateFixedSpacingRight(dataCache.scriptDoubleToVarchar("bp1"),",", 3)+"||'-'||"+dataCache.scriptSeparateFixedSpacingRight(dataCache.scriptDoubleToVarchar("bp2"),",", 3)+" AS location");
				SELECT("'HYPERLINK(\"http://genome.ucsc.edu/cgi-bin/hgTracks?&org=Human&db=hg19&position='||chr||'%3A'||"+dataCache.scriptDoubleToVarchar("bp1")+"||'-'||"+dataCache.scriptDoubleToVarchar("bp2")+"||'\",\"ucsc\")' AS UCSC_LINK");
				FROM(schemaName+"._USER_INPUT");
				ORDER_BY("INPUTID,CHR,BP1,BP2");
			}
		};
		
		q=userInput.toString();
		dataCache.table("USER_INPUT", q).commit(); //candidate
		dataCache.index("USER_INPUT", "INPUTID");
		dataCache.index("USER_INPUT", "CHR");
		dataCache.index("USER_INPUT", "BP1");
		dataCache.index("USER_INPUT", "BP2");
		dataCache.index("USER_INPUT", "GENENAME");
		dataCache.index("USER_INPUT", "SNPID");
		dataCache.index("USER_INPUT", "PVALUE");
		
		printTimeMeasure();
		System.out.println("USER_INPUT");
		
		
		//GENE_MASTER EXPANDED
		q=new SQL()
		{
			{
				SELECT("g.*");
				SELECT("(g.bp1-20000) AS bp1s20k_gm"); //expand by 20kb;
				SELECT("(g.bp2+20000) AS bp2a20k_gm");
				SELECT("(g.bp1-10e6) AS bp1s10m_gm"); //expand by 10mb;
				SELECT("(g.bp2+10e6) AS bp2a10m_gm");
				FROM(schemaName+".GENE_MASTER g");
			}
		}.toString();
		dataCache.view("GENE_MASTER_EXPANDED", q).commit();
		
		/*
		//*====== Candidate genes (all) for bioinformatics ======;
		//*=== Genes: GENCODE v17 genes in bin, expand by 20kb;
		
		q=new SQL()
		{
			{
				//SELECT("c.chr AS chr_in"); //hg19chrc,	r0
				//SELECT("c.bp1 AS bp1_in"); //six1, 		r1
				//SELECT("c.bp2 AS bp2_in"); //six2, 		r2
				SELECT("c.*");
				SELECT("g.chr AS chr_gm");
				SELECT("g.bp1 AS bp1_gm");
				SELECT("g.bp2 AS bp2_gm");
				SELECT("g.genename AS genename_gm");
				SELECT("g.entrez AS entrez_gm");
				SELECT("g.ensembl AS ensembl_gm");
				SELECT("g.strand AS strand_gm");
				
				FROM(schemaName+".USER_INPUT c");
				
				INNER_JOIN(schemaName+".GENE_MASTER_EXPANDED g ON (c.chr=g.chr AND "+dataCache.scriptTwoSegmentOverlapCondition("c.bp1","c.bp2","g.bp1s20k_gm","g.bp2a20k_gm")+")");
				ORDER_BY("INPUTID,chr,bp1,bp2");
			}
		}.toString();
		dataCache.table("GENES_IN_INTERVAL", q).commit();
		dataCache.index("GENES_IN_INTERVAL", "INPUTID");
		dataCache.index("GENES_IN_INTERVAL", "chr");
		dataCache.index("GENES_IN_INTERVAL", "bp1");
		dataCache.index("GENES_IN_INTERVAL", "bp2");
		dataCache.index("GENES_IN_INTERVAL", "pvalue");
		dataCache.index("GENES_IN_INTERVAL", "chr_gm");
		dataCache.index("GENES_IN_INTERVAL", "bp1_gm");
		dataCache.index("GENES_IN_INTERVAL", "bp2_gm");
		dataCache.index("GENES_IN_INTERVAL", "genename_gm");
		
		printTimeMeasure();
		System.out.println("GENES_IN_INTERVAL"); //allgenes20kb
		*/
		
		//*====== Candidate genes, PC & distance ======;
		//* expand by 10mb;
		
		
		//* join;
		q=new SQL()
		{
			{
				SELECT("c.*");
				//SELECT("g.chr AS chr_gm");
				SELECT("g.bp1 AS bp1_gm, g.bp2 AS bp2_gm, g.genename AS genename_gm, g.entrez AS entrez_gm, g.ensembl AS ensembl_gm, g.ttype AS ttype_gm, g.strand AS strand_gm, g.product AS product_gm");
				SELECT("( CASE WHEN ("+dataCache.scriptTwoSegmentOverlapCondition("c.bp1","c.bp2","g.bp1","g.bp2")+") THEN 0 WHEN c.bp1 IS NULL OR c.bp2 IS NULL THEN 9e9 ELSE NUM_MAX_INTEGER(ABS(c.bp1-g.bp2),ABS(c.bp2-g.bp1)) END) dist");
				FROM(schemaName+"._USER_INPUT c");
				//INNER_JOIN(schemaName+".GENE_MASTER_EXPANDED g ON (g.ttype='protein_coding' AND c.chr=g.chr AND ("+dataCache.scriptTwoSegmentOverlapCondition("c.bp1","c.bp2","g.bp1s10m_gm","g.bp1")+" OR "+dataCache.scriptTwoSegmentOverlapCondition("c.bp1","c.bp2","g.bp2","g.bp2a10m_gm")+"))");
				INNER_JOIN(schemaName+".GENE_MASTER_EXPANDED g ON (g.ttype='protein_coding' AND c.chr=g.chr AND "+dataCache.scriptTwoSegmentOverlapCondition("c.bp1","c.bp2","g.bp1s10m_gm","g.bp2a10m_gm")+")");
				ORDER_BY("INPUTID,chr,bp1,bp2");
			}
		}.toString();
		dataCache.table("PROTEIN_CODING_GENES_ALL", q).commit(); //earlier GENES_PROTEIN_CODING
		dataCache.index("PROTEIN_CODING_GENES_ALL", "INPUTID");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "chr");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "bp1");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "bp2");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "pvalue");
		//dataCache.index("PROTEIN_CODING_GENES_ALL", "chr_gm");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "bp1_gm");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "bp2_gm");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "genename_gm");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "entrez_gm");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "ensembl_gm");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "ttype_gm");
		dataCache.index("PROTEIN_CODING_GENES_ALL", "strand_gm");
		//dataCache.index("PROTEIN_CODING_GENES_ALL", "product_gm");
		
		printTimeMeasure();
		System.out.println("PROTEIN_CODING_GENES_ALL"); //genesPC10m
		
		//*====== PC genes near======;
		
		q=new SQL()
		{
			{
				SELECT("g.*");
				FROM(schemaName+".PROTEIN_CODING_GENES_ALL g");
				WHERE("dist<100000");
				ORDER_BY("INPUTID,dist,ensembl_gm");
			}
		}.toString();
		dataCache.view("PROTEIN_CODING_GENES", q).commit(); //genesPCnear
		
		printTimeMeasure();
		System.out.println("PROTEIN_CODING_GENES");
		
		
		
		/* LINKING */
		
		
		
		
		//*=== gwas catalog;
				
		q=new SQL()
		{
			{
				SELECT("c.*, r.bp1 AS bp1_gwas, r.snpid AS snpid_gwas, r.pvalue AS pvalue_gwas, r.pmid AS pmid_gwas, r.trait AS trait_gwas");
				FROM(schemaName+"._USER_INPUT c");
				INNER_JOIN(schemaName+"._gwas_catalog r ON c.chr=r.chr AND "+dataCache.scriptTwoSegmentOverlapCondition("c.bp1", "c.bp2", "r.bp1", "r.bp2"));
				ORDER_BY("INPUTID,pvalue_gwas,snpid_gwas");
			}
		}.toString();
		dataCache.table("gwas_catalog", q).commit();
		
		printTimeMeasure();
		System.out.println("gwas_catalog");
		
		
		//*=== omim;
		q=new SQL()
		{
			{
				SELECT("g.*, r.OMIMgene AS omimgene_omim, r.OMIMDisease AS omimdisease_omim, r.type AS type_omimi");
				FROM(schemaName+".PROTEIN_CODING_GENES g");
				INNER_JOIN(schemaName+"._omim r ON g.genename_gm=r.geneName AND g.geneName_gm IS NOT NULL AND g.geneName_gm!='' AND r.geneName IS NOT NULL AND r.geneName!=''");
				ORDER_BY("INPUTID,dist,omimgene_omim,genename_gm");
			}
		}.toString();
		dataCache.table("omim", q).commit();
		
		printTimeMeasure();
		System.out.println("omim");
		
		//*=== psych CNVs;
		q=new SQL()
		{
			{
				SELECT("c.*, r.chr AS chr_r, r.bp1 AS bp1_r, r.bp2 AS bp2_r, r.disease AS disease_r, r.type AS type_r, r.note AS note_r");
				FROM(schemaName+"._USER_INPUT c");
				INNER_JOIN(schemaName+"._psychiatric_cnvs r ON c.chr=r.chr AND "+dataCache.scriptTwoSegmentOverlapCondition("c.bp1", "c.bp2", "r.bp1", "r.bp2"));
				ORDER_BY("INPUTID,disease_r,type_r");
			}
		}.toString();
		dataCache.table("psychiatric_cnvs", q).commit();
		
		printTimeMeasure();
		System.out.println("psychiatric_cnvs");
		
		
		//*=== asd genes;
		q=new SQL()
		{
			{
				SELECT("g.*, r.type AS type_asd");
				FROM(schemaName+".PROTEIN_CODING_GENES g");
				INNER_JOIN(schemaName+"._asd_genes r ON g.genename_gm=r.geneName AND g.geneName_gm IS NOT NULL AND g.geneName_gm!='' AND r.geneName IS NOT NULL AND r.geneName!=''");
				ORDER_BY("INPUTID,dist,type_asd,genename_gm");
			}
		}.toString();
		dataCache.table("asd_genes", q).commit();
		
		printTimeMeasure();
		System.out.println("asd_genes");
		
		
		//*=== id/dev delay ;
		q=new SQL()
		{
			{
				SELECT("g.*, r.type AS type_id_dd");
				FROM(schemaName+".PROTEIN_CODING_GENES g");
				INNER_JOIN(schemaName+"._id_devdelay_genes r ON g.geneName_gm=r.geneName AND g.geneName_gm IS NOT NULL AND g.geneName_gm!='' AND r.geneName IS NOT NULL AND r.geneName!=''");
				ORDER_BY("INPUTID,dist,type_id_dd,genename_gm");
			}
		}.toString();
		dataCache.table("id_devdelay_genes", q).commit();
		
		printTimeMeasure();
		System.out.println("id_devdelay_genes");
		
		
		//*=== mouse knockout, jax;
		q=new SQL()
		{
			{
				SELECT("g.*, r.musName AS musname_r, r.phenotype AS phenotype_r");
				FROM(schemaName+".PROTEIN_CODING_GENES g");
				INNER_JOIN(schemaName+"._mouse_knockout r ON g.geneName_gm=r.geneName AND g.geneName_gm IS NOT NULL AND g.geneName_gm!='' AND r.geneName IS NOT NULL AND r.geneName!=''");
				ORDER_BY("INPUTID,dist,ensembl_gm");
			}
		}.toString();
		dataCache.table("mouse_knockout", q).commit();
		
		printTimeMeasure();
		System.out.println("mouse_knockout");
		
		
		System.out.println("Operations done");
		System.out.println("Operations time: "+(System.nanoTime()- operationStartTimeNanos)/1E9+" seconds");
		
	}
	
}
