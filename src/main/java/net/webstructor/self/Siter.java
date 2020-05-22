/*
 * MIT License
 * 
 * Copyright (c) 2005-2020 by Anton Kolonin, Aigents®
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.webstructor.self;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import net.webstructor.agent.Body;
import net.webstructor.al.AL;
import net.webstructor.al.All;
import net.webstructor.al.Iter;
import net.webstructor.al.Parser;
import net.webstructor.al.Period;
import net.webstructor.al.Reader;
import net.webstructor.al.Seq;
import net.webstructor.al.Time;
import net.webstructor.al.Writer;
import net.webstructor.cat.HttpFileReader;
import net.webstructor.cat.StringUtil;
import net.webstructor.comm.Socializer;
import net.webstructor.core.Actioner;
import net.webstructor.core.Environment;
import net.webstructor.core.Property;
import net.webstructor.core.Storager;
import net.webstructor.core.Thing;
import net.webstructor.data.Graph;
import net.webstructor.data.ContentLocator;
import net.webstructor.peer.Peer;
import net.webstructor.util.Array;
import net.webstructor.util.MapMap;
import net.webstructor.util.Str;

public class Siter {

	public static final int DEFAULT_RANGE = 1;//TODO: make configurable
	public static String punctuation = null; //".";//to maintain the sentence structure
	
	enum Mode { SMART, TRACK, FIND };
	
	Body body;
	Thing self;
	Storager storager;
	private String thingname;
	String rootPath;
	Date realTime; // real time to use for page refreshment
	Date timeDate; // = Time.day(Time.today); // database time to use for storage
	boolean forced;//forcing peer: if not null - re-read unconditionally, if null - read only if content is changed
	boolean strict;//true - follow same-domain links only, false - follow all links
	Collection focusThings;
	Collection allThings;
	MapMap thingPaths; //thing->path->instance
	MapMap thingTexts; //thing->text->instance	
	ContentLocator imager;
	ContentLocator linker;//using Imager to keep positions of links
	ContentLocator titler;
	Cacher cacher;
	long tillTime;
	long newsLimit;
	int range;//TODO unify with PathFinder's hopLimit
	Mode mode;//mode - [smart|track|find] - whether to use existing path if present only (track) or always explore new paths (find) or track first and go to find if nothing is found (default - smart)

	public Siter(Body body,Storager storager,String path) {
		this.body = body;
		this.self = body.self();
		this.rootPath = path;
		this.storager = storager;
		thingPaths = new MapMap();
		thingTexts = new MapMap();
		imager = new ContentLocator();
		linker = new ContentLocator();
		titler = new ContentLocator();
		this.cacher = body.filecacher;
	}
	
	public Siter init(String thingname,Date time,boolean forced,long tillTime,int range,int limit,boolean strict,String mode) {
		this.thingname = thingname;
		this.realTime = time;
		this.timeDate = Time.date(time);
		this.forced = forced;
		this.strict = strict;
		this.tillTime = tillTime;
		this.range = range < 0 ? 0 : range;
		this.newsLimit = limit;
		this.mode = "track".equalsIgnoreCase(mode) ? Mode.TRACK : "find".equalsIgnoreCase(mode) ? Mode.FIND : Mode.SMART;
		
		//first, try to get things specified by thing name name (if explicitly provided)
		allThings = storager.getNamed(thingname);
		if (AL.empty(allThings)) {
			//next, try to find all things people are aware of:
			//build list of things that are known to and trusted by users of given path
			allThings = new HashSet();
			//TODO: make sites readable not being listed?
			java.util.Set sites = storager.get(AL.sites,rootPath);
			java.util.Set peers = !AL.empty(sites)? new HashSet(sites) : null;
			java.util.Set peerSiteTrusts = storager.get(AL.trusts,rootPath);
			if (!AL.empty(peers) && !forced){//get things from peers only trusting this site
				if (AL.empty(peerSiteTrusts))
					peers.clear();
				else
					peers.retainAll(peerSiteTrusts);
			}
			if (!AL.empty(peers)){
				Date since = Time.today(-body.attentionDays());
				for (Iterator it = peers.iterator(); it.hasNext();){
					Thing peer = (Thing)it.next();
					int news_limit = StringUtil.toIntOrDefault(peer.getString(Peer.news_limit),10,3);
					this.newsLimit = Math.max(this.newsLimit, news_limit);
					Date activityTime = (Date)peer.get(Peer.activity_time);
					Collection topics = peer.getThings(AL.topics);
					if (activityTime != null && activityTime.compareTo(since) >= 0 && !AL.empty(topics)){
						Collection peerThings = new HashSet(topics);
						if (!forced){
							Collection peerTrusts = peer.getThings(AL.trusts);
							if (AL.empty(peerTrusts))
								peerThings.clear();
							else
								peerThings.retainAll(peerTrusts);
						}
						for (Iterator jt = peerThings.iterator(); jt.hasNext();){
							Thing thing = (Thing)jt.next();
//TODO: optimize this							
							Set peerThingTrusts = storager.get(AL.trusts,thing);
							if (!AL.empty(peerThingTrusts))//if there is at least one peer trusting the thing
								if (forced || Array.intersect(peerThingTrusts,peerSiteTrusts)){
									allThings.add(thing);
								}
						}
					}
				}
			}
		}
		return this;
	}
	
	boolean expired(){
		boolean expired = tillTime > 0 && System.currentTimeMillis() > tillTime;
		if (expired)
			body.debug("Site crawling time out:"+rootPath);
		return expired;
	}
	
	//TODO add Crawler interface with readChannel => crawl method
	//TODO make Redditer, Twitter, Discourse => implement Crawler 
	//TODO split Siter into Siter framwork and Spider   
	//TODO make spider to implement Crawler 
	//TODO Siter.newSpider => Spider.crawl
	private int newSpider(Collection topics) {
		int hits = 0;
		for (Object topic : topics){
			Thing t = (Thing)topic;
			if (expired())
				break;
			Collection goals = new ArrayList(1);
			goals.add(t);
			String name = t.getName();
			body.reply("Site crawling thing begin "+name+" in "+rootPath+".");
			boolean found = new PathTracker(this,goals,range).run(rootPath);
			body.reply("Site crawling thing end "+(found ? "found" : "missed")+" "+name+" in "+rootPath+".");
			if (found)
				hits++;
		}
		return hits;
	}
	
	public boolean read() {
		cacher.clearTodos();
		Collection topics = !AL.empty(thingname) ? storager.getNamed(thingname) : allThings;
		body.debug("Site crawling root begin "+rootPath+".");
		long start = System.currentTimeMillis(); 

		boolean ok = false;
		for (Socializer s : body.getSocializers())//try channel-readers first
			if (s.readChannel(rootPath, topics, thingPaths) >= 0) {
				ok = true;
				break;
			}
		if (!ok)//use no channel-reader responded, try site-reader as fallback
			ok = newSpider(topics) > 0;
			

		if (ok)//send updates on success
			ok = update() > 0;
			
		long stop = System.currentTimeMillis(); 
		body.debug("Site crawling root end "+(ok ? "found" : "missed")+" "+rootPath+", took "+Period.toHours(stop-start));
		return ok;
	}
	
	private int update(){
		int hits = update(body,rootPath,timeDate,thingPaths,forced,null);
		thingPaths.clear();//help gc
		thingTexts.clear();
		cacher.clearTodos();
		return hits;
	}
	
	public static int update(Body body,String rootPath,Date time,MapMap thingPaths,boolean forced,Thing context){
		Object[] things = thingPaths.getKeyObjects();
		if (AL.empty(things))
			return 0;
		int hits = 0;
		Date now = Time.date(time);
		HashMap<Thing,Thing> existings = new HashMap<Thing,Thing>(); 
		//update whatever is novel compared to snapshot in STM or LTM
		for (int p = 0; p < things.length; p++){
			Thing thing = (Thing)things[p];
			Object[] paths = thingPaths.getSubKeyObjects(thing);
			ArrayList collector = new ArrayList();
			for (int i = 0; i < paths.length; i++){
				String path = (String)paths[i];
				Collection instances = thingPaths.getObjects(thing, path);
				for (Iterator it = instances.iterator(); it.hasNext();){
					Thing instance = (Thing)it.next();
					//Collection ises = instance.getThings(AL.is);
					String text = instance.getString(AL.text);
					//if (!AL.empty(ises) && !AL.empty(text)){
						//String thingName = ((Thing)ises.iterator().next()).getName();
					if (!AL.empty(text)){
						String thingName = thing.getName();
					
//TODO: use "forced" consistently						
//TODO: make sure if GLOBAL novelty is required, indeed... 
//TODO: use "newly existing" logic same as used in archiver.exists!?
//TODO: use either existing = existing(... thing or instance to update snapshots on the next round below????
						Thing existing = existing(body,thing,instance,null,false,text);
						if (existing != null) {//new path-less identity
							existings.put(instance,existing);
							continue;
						}
						//checking for existence before today, not just today... 
						Date date = null;//instance.getDate(AL.times,null);
						if (!forced && body.archiver.exists(thingName,text,date))//check LTM
							continue;
					
						hits++;
						instance.store(body.storager);
						if (!AL.empty(path))
							try {
								body.storager.add(instance, AL.sources, path);
							} catch (Exception e) {
								body.error(e.toString(), e);
							}
						collector.add(instance);
					}
				}
				//update(storager,thing,instances,rootPath);
				//TODO: real path here!!??
				//update(storager,thing,instances,path);
			}
			update(body,body.storager,thing,collector,rootPath,context);
		}		
		//memorize everything known and novel in STM AND LTM snapshots
//TODO: optimization to avoid doing extra stuff below!!!		
		for (int j = 0; j < things.length; j++){
			Thing thing = (Thing)things[j];
			Object[] paths = thingPaths.getSubKeyObjects(thing);
			for (int i = 0; i < paths.length; i++){
				String path = (String)paths[i];
				Collection instances = thingPaths.getObjects(thing, path);
				for (Iterator it = instances.iterator(); it.hasNext();){
					Thing instance = (Thing)it.next();
					//Collection ises = instance.getThings(AL.is);
					String text = instance.getString(AL.text);
					//if (!AL.empty(ises) && !AL.empty(text)){
						//String thingName = ((Thing)ises.iterator().next()).getName();
					if (!AL.empty(text)){
						String thingName = thing.getName();
						Thing existing = existings.get(instance);
//TODO: use "forced" consistently						
						if (!forced && existing != null)//new path-less identity
							existing.set(AL.times,now);//update temporal snapshot in-memory
//TODO don't need not-a-news-item things hanging in memory?
						//if (existing == null) {
						//	instance.store(body.storager);
						//	instance.set(AL.times,now);
						//}
						
						body.archiver.update(thingName,text,now);//update temporal snapshot in LTM
					}
				}
			}
		}
		return hits;
	}
	
	boolean linkMatch(String text,Seq patseq) {
		Iter iter = new Iter(Parser.parse(text));
		try {
			if (Reader.read(iter, patseq, null))
				return true;
		} catch (Throwable e) {
			body.error("Siter linkMatch error pattern "+patseq, e);
		}
		return false;
	}

	private void index(String path,Date time,Iter iter,ArrayList links){
		if (body.sitecacher != null){
			//TODO: actual time of the page
			Graph g = body.sitecacher.getGraph(Time.day(time));//daily graph
			//index links
			if (!AL.empty(links))
			for (Iterator it = links.iterator(); it.hasNext();){
				String[] link = (String[])it.next();
				String linkUrl = HttpFileReader.alignURL(path,link[0],false);//relaxed, let any "foreign" links
				//String linkText = link[1];//TODO: use to evaluate source name as the best-weighted link!?
				if (!AL.empty(linkUrl) && g.getValue(path, linkUrl, "links") == null){
					g.addValue(path, linkUrl, "links", 1);
					g.addValue(linkUrl, path, "linked", 1);
				}
			}
			//index words (site->words->word, word->worded->site)
//TODO: check configuration if need word index!?
//TODO prevent double-triple-etc indexing on repeated reads!?
//TODO unify with path in in-text ...
			for (iter.pos(0); iter.has();){
				Object o = iter.next();
				//g.addValue(path, o, "words", 1);
				g.addValue(o, path, "worded", 1);
			}			
		}
	}
	
	boolean readPage(String path,ArrayList links,Collection things) {
		Thread.yield();
		boolean result = false;
		boolean skipped = false;
		boolean failed = false;
		String text = null;
		
		body.reply("Site crawling page begin "+path+".");
		if (!AL.isURL(path)) // if not http url, parse the entire text
			//result = match(storager,path,time,null,things);
			result = match(storager,new Iter(Parser.parse(path)),null,timeDate,null,things);//with no positions
		else
		//TODO: distinguish skipped || failed in readIfUpdated ?
		if (!AL.empty(text = cacher.readIfUpdated(path,links,imager.getMap(path),linker.getMap(path),titler.getMap(path),forced,realTime))) {
			ArrayList positions = new ArrayList();
			//Iter iter = new Iter(Parser.parse(text,positions));//build with original text positions preserved for image matching
			Iter iter = new Iter(Parser.parse(text,null,false,true,true,false,punctuation,positions));//build with original text positions preserved for image matching
			result = match(storager,iter,positions,timeDate,path,things);
			index(path,timeDate,iter,links);
			//TODO: add source name as page title by default?
		} else {
			skipped = true;//if not read 
			failed = true;
		}
		if (skipped || failed)
			body.reply("Site crawling page end "+(failed? "failed": "skipped")+" "+path+".");
		else
			body.reply("Site crawling page end "+(result? "found": "missed")+" "+path+".");
		return result;
	}

	//get all things for the thing name
	//private boolean match(Storager storager,String text,Date time,String path,Collection things) {
	private boolean match(Storager storager,Iter iter,ArrayList positions,Date time,String path,Collection things) {
		int matches = 0;
		//if (text != null) {
		if (iter != null && iter.size() > 0) {
			if (!AL.empty(things)) {
				for (Iterator it = things.iterator();it.hasNext();)
					//matches += match(storager,text,(Thing)it.next(),time,path);
					matches += match(storager,iter,positions,(Thing)it.next(),time,path);
			}
		}
		return matches > 0;
	}
	
	//match all Patterns of one Thing for one Site and send updates to subscribed Peers
	//TODO: Siter extends Matcher (MapMap thingTexts, MapMap thingPaths, Imager imager, Imager linker)
	public static int match(Storager storager,Iter iter,ArrayList positions,Thing thing,Date time,String path, MapMap thingTexts, MapMap thingPaths, ContentLocator imager, ContentLocator linker, ContentLocator titler) {
		//TODO: re-use iter building it one step above
		//ArrayList positions = new ArrayList();
		//Iter iter = new Iter(Parser.parse(text,positions));//build with original text positions preserved for image matching
		int matches = 0;
		//first, try to get patterns for the thing
		Collection patterns = (Collection)thing.get(AL.patterns);
		//next, if none, create the pattern for the thing name manually
		if (AL.empty(patterns))
			//auto-pattern from thing name split apart
			matches += match(storager,thing.getName(),iter,thing,time,path,positions, thingTexts, thingPaths, imager, linker, titler);
		if (!AL.empty(patterns)) {
			for (Iterator it = patterns.iterator(); it.hasNext();){				
                matches += match(storager,((Thing)it.next()).getName(),iter,thing,time,path,positions, thingTexts, thingPaths, imager, linker, titler);
			}
		}
		return matches;
	}
	
	private int match(Storager storager,Iter iter,ArrayList positions,Thing thing,Date time,String path) {
		return match(storager, iter, positions, thing, time, path, thingTexts, thingPaths, imager, linker, titler);
	}
	
	//TODO: move out?
	static boolean has(Storager storager, Thing thing, String property, String name) {
		Collection named = storager.getNamed(name);
		Object props = thing.get(property);
		if (props instanceof Collection && ((Collection)props).containsAll(named))
			return true;
		return false;
	}
	
	//TODO: move out to time-specific framework!?
	static Collection latest(Storager storager, Thing is, String path) {
		HashSet found = new HashSet();
		long latest = 0;
		Collection instances = storager.get(AL.is,is);
		if (!AL.empty(instances)) {
			//TODO: do we really need the clone here to prevent ConcurrentModificationException? 
			for (Iterator it = new ArrayList(instances).iterator(); it.hasNext();) {
				Thing instance = (Thing)it.next();
//String debug_text = Writer.toPrefixedString(null, null, instance);				
				if (path != null && !has(storager,instance,AL.sources,path))
					continue;
				Date date = Time.day(instance.get(AL.times));
				long time = date == null ? 0 : date.getTime();
				if (time >= latest) {
					if (time > latest)
						found.clear();
					found.add(instance);
					latest = time;
				}
			}
		}
		return found;
	}

	/*static private Thing existingNonPeiodic(Body body, Thing thing, Thing instance, String path, boolean debug, String text) {
		Collection coll;
		try {
			All query = new All(new Object[]{
					new Seq(new Object[] {AL.is,thing}),
					new Seq(new Object[] {AL.text,text})});
			coll = body.storager.get(query,(Thing)null);
			if (!AL.empty(coll))
				return (Thing)coll.iterator().next();
		} catch (Exception e) {
			body.error("Spidering existence check failed ",e);
		}
		return null;
	}*/
	
	static Thing existing(Body body, Thing thing, Thing instance, String path, boolean debug, String text) {
		Collection coll = latest(body.storager,thing,path);
		if (debug) {
			body.debug("thing   :"+Writer.toString(thing));
			body.debug("instance:"+Writer.toString(instance));
			body.debug("path    :"+path);
			body.debug("coll    :");
		}
		if (!AL.empty(coll))
			for (Iterator it = coll.iterator(); it.hasNext();) {
				Thing latest = (Thing)it.next();
				if (debug)
					body.debug("latest  :"+latest);
				String latestText = latest.getString(AL.text);
				if (!AL.empty(text) && !AL.empty(latestText)){//TODO: validate by text!!!??? 
					if (text.equals(latestText)){
						if (debug)
							body.debug("novel   :false");
						return latest;
					}
				} else
				if (body.storager.match(latest, instance)) {
					if (debug)
						body.debug("novel   :false");
					return latest;
				}
			}
		if (debug)
			body.debug("novel   :true");
		return null;
	}

	public static String shortTitle(String text) {
		if(text.matches("(?![0-9]).*["+AL.punctuation+"](?![0-9]).*")) {
			String[] tokens = text.split("["+AL.punctuation+"]");
			for(String s : tokens) {
				while(s.endsWith(" "))
					s = s.substring(0, s.length()-1);
				while(s.startsWith(" "))
					s = s.substring(1, s.length());
				if(s.contains(" "))
					return s;
			}
		}
		return text;
	}

	//TODO: move to other place
	public static Seq relaxPattern(Storager storager, Thing instance, String context, Seq patseq, String about) {
		if (AL.empty(patseq))
			return patseq;
		Object pat[] = new Object[patseq.size() + (context == null ? 0 : 1) + (about == null ? 0 : 1)];
		int i = 0;
		if (context != null)
			pat[i++] = new Property(storager,instance,context);
		for (int j = 0; j < patseq.size(); j++)
			pat[i++] = patseq.get(j);
		if (about != null)
			pat[i++] = new Property(storager,instance,about);
		return new Seq(pat);
	}

	//TODO: make smarter patterns like "[?prefix patseq ?postfix]" and make them supported by matcher!?
	public static boolean readAutoPatterns(Storager storager, Iter iter, Seq patseq, Thing instance, StringBuilder summary) {
		if (!Property.containedIn(patseq)){
			if (Reader.read(iter, relaxPattern(storager, instance,"context",patseq,"about"), summary))
				return true;
			if (Reader.read(iter, relaxPattern(storager, instance,null,patseq,"about"), summary))
				return true;
			if (Reader.read(iter, relaxPattern(storager, instance,"context",patseq,null), summary))
				return true;
		}
		return Reader.read(iter, patseq, summary);
	}

	//match one Pattern for one Thing for one Site
	public static int match(Storager storager,String patstr, Iter iter, Thing thing, Date time, String path, ArrayList positions, MapMap thingTexts, MapMap thingPaths, ContentLocator imager, ContentLocator linker, ContentLocator titler) {
		Date now = Time.date(time);
		int matches = 0;
		//TODO:optimization so pattern with properties is not rebuilt every time?
		iter.pos(0);//reset 
		for (;;) {
			Thing instance = new Thing();
			Seq patseq = Reader.pattern(storager,instance, patstr);
			
			StringBuilder summary = new StringBuilder();
			boolean read = readAutoPatterns(storager,iter,patseq,instance,summary);
			if (!read)
				break;
			
			//plain text before "times" and "is" added
			String nl_text = summary.toString();
			String title_text = "";
			if(titler != null &&
			   titler.getMap(path).size() > 0 &&
			   titler.getMap(path).containsKey(path) &&
			   !titler.getMap(path).get(path).toString().equals(""))
					title_text = titler.getMap(path).get(path).toString();
			if(AL.empty(title_text))
				title_text = shortTitle(nl_text);

			//TODO check in mapmap by text now!!!
			//TODO if matched, get the "longer" source path!!!???
			if (thingTexts != null && thingTexts.getObject(thing, nl_text, false) != null)//already adding this
				continue;

			/*
			//TODO: ensure if such GLOBAL "path-less" novelty (ditto above) is required
			//check if latest in LTM
			if (forcer == null && body.archiver.exists(patstr,nl_text)){
				body.archiver.update(patstr,nl_text,now);//update temporal snapshot in LTM
				continue;
			}
			*/
			
//TODO: move all that validation and serialization to updater!!!
//first, add new entities
//next, memorize new snapshot
//also, don't add "is thing" to not memorized instance? 
			
			instance.addThing(AL.is, thing);
			instance.set(AL.times, now);
			instance.setString(AL.text,nl_text);
			instance.setString(AL.title, title_text);
			Integer textPos = positions == null ? new Integer(0) : (Integer)positions.get(iter.cur() - 1);
			if (imager != null){
				String image = imager.getAvailable(path,textPos);
				if (!AL.empty(image))
					instance.setString(AL.image,image);
			}
			String link = null;
			if (linker != null){
				//measure link pos as link_pos = (link_beg+link_end)/2
				//associate link with text if (text_pos - link_pos) < text_legth/2, where text_pos = (text_beg - text_end)/2
				int range = nl_text.length()/2;
				int text_pos = textPos.intValue() - range;//compute position of text as its middle
				link = linker.getAvailableInRange(path,new Integer(text_pos),range);
			}
			if (thingTexts != null)
				thingTexts.putObject(thing, nl_text, instance);
			if (thingPaths != null)
				thingPaths.putObjects(thing, !AL.empty(link)? link : path == null ? "" : path, instance);
			
			matches++;
		}
		return matches;
	}

	/**
	 * Returns array of [subject,content]
	 * @param thing
	 * @param path
	 * @param news
	 * @return
	 */
	static String[] digest(Body body, Thing thing, String path, Collection news, boolean verbose){
		if (AL.empty(news))//no news - no digest
			return null;
		//StringBuilder subject = new StringBuilder();
		StringBuilder content = new StringBuilder();
		String[] unneededNames = new String[]{AL.is,AL.times,AL.sources,AL.text};
		//String best = "";
		if (!AL.empty(path))
			content.append(path).append('\n');
		//TODO: group by real path under common root path and have only one path per same-real-path group in digest
		//(assuming the news list is already pre-grouped by real paths - which is true given the entiere implmementation of "The Siter")
		String currentPath = null;
		for (Iterator it = news.iterator(); it.hasNext();) {
			Thing t = (Thing)it.next();
			String nl_text = t.getString(AL.text);

			//TODO:more intelligent approach for subject ("title") formation?

			//real path
			Collection sources = t.getThings(AL.sources);
			if (!AL.empty(sources)){
				String source = ((Thing)sources.iterator().next()).getName();
				if (!AL.empty(source) && !source.equals(path)){
					if (currentPath == null || !currentPath.equals(source))
						content.append(source).append('\n');
					currentPath = source;
				}
			}
				
			content.append('\"').append(nl_text).append('\"').append('\n');
			if (verbose){
				String[] names = Array.sub(t.getNamesAvailable(), unneededNames);
				if (!AL.empty(names)){
					Writer.toString(content, t, null, names, true);//as a form
					content.append('\n');
				}
			}
		}
		return new String[]{thing.getName(),content.toString()};
	}
	
	//TODO: if forcer is given, don't update others
	//- send updates (push notifications)
	//-- Selfer: for a news for thing, send email for all its users (not logged in?) 
	static void update(Body body, Storager storager, Thing thing,Collection news,String path,Thing group) {	
		Object[] topics = {AL.topics,thing};
		Object[] sites = {AL.sites,path};
		Object[] topics_trusts = {AL.trusts,thing};
		Object[] sites_trusts = {AL.trusts,path};
		All query = group != null ? new All(new Object[]{new Seq(topics),new Seq(topics_trusts),new Seq(new Object[]{AL.groups,group})})
				: !AL.empty(path) ? new All(new Object[]{new Seq(topics),new Seq(sites),new Seq(topics_trusts),new Seq(sites_trusts)})
				: new All(new Object[]{new Seq(topics),new Seq(topics_trusts)});//TODO: more restrictive!?
		try {
			Collection peers = storager.get(query,(Thing)null);//forcer?
			if (!AL.empty(peers))
				update(body,storager,thing,news,path,peers,group == null);//verbose digests only for sites!?
		} catch (Exception e) {
			body.error("Spidering update failed ",e);
		}
	}

	public static void update(Body body, Storager storager, Thing thing,Collection news,String path,Collection peers,boolean verbose) throws IOException {
//TODO: make digest individual for peers, generated inside the peer-specific update() method
		String[] digest = digest(body,thing,path,news,verbose);
		if (AL.empty(digest))
			return;
		for (Iterator it = peers.iterator(); it.hasNext();) {
			Thing peer = (Thing)it.next();
			update(body,storager,peer,thing,news,digest[0],digest[1],body.signature());
			Collection allSharesTos = getSharesTos(storager,peer);
			if (!AL.empty(allSharesTos)) for (Iterator tit = allSharesTos.iterator(); tit.hasNext();)
				update(body,storager,(Thing)tit.next(),thing,news,digest[0],digest[1],signature(body,peer));
		}
	}
	
	public static String signature(Body body,Thing peer){
		return peer.getTitle(Peer.title_email)+" at "+body.site();
	}
	
	/*static Collection getSharesTos(Storager storager, Thing peer){
		//TODO: make this more efficient
		//get list of peers trusted by the peer:
		//1) get all peers that are marked to share to by this peer
		Collection allSharesTos = (Collection)peer.get(AL.shares);
		if (!AL.empty(allSharesTos)) {
			//TODO: test if it works
			Set trustingPeers = storager.get(AL.trusts,peer);
			if (!AL.empty(trustingPeers)){
				trustingPeers = new HashSet(trustingPeers);
				//2) restrict all peers with those who have the shares
				trustingPeers.retainAll(allSharesTos);
				return trustingPeers;
			}
		}
		return null;
	}*/
	
	static Collection getSharesTos(Storager storager, Thing peer){
		Set trustingPeers = storager.get(AL.trusts,peer);//all who trusts us (subscribers)
		Collection allShares = (Collection)peer.get(AL.shares);//all who we share to plus our shared areas
		if (!AL.empty(allShares) && !AL.empty(trustingPeers)) {//we need to share something plus should have someone who trusts us
			trustingPeers = new HashSet(trustingPeers);
			//check if this peer is public
			Collection areas = (Collection)peer.get(AL.areas);
			if (!AL.empty(areas)) {
				areas = new HashSet(areas);
				areas.retainAll(allShares);
			}
			if (AL.empty(areas))//if have public areas, return all trustees, otherwise:
				trustingPeers.retainAll(allShares);//leave only the peers who are explicitly shared
			return trustingPeers;
		}
		return null;
	}
	
	static void update(Body body, Storager storager, Thing peer, Thing thing, Collection news, String subject, String content, String signature) throws IOException {
		for (Iterator it = news.iterator(); it.hasNext();) {
			Thing t = (Thing)it.next();
//TODO: eliminate duplicated !!!untrusted things here on peer-specific basis!!!???
			t.set(AL._new, AL._true, peer);
		}
		body.update(peer, subject, content, signature);
	}

	//update all trusting peers being shared
	public static Actioner getUpdater(){
		return new Actioner(){
			@Override
			public boolean act(Environment env, Storager storager, Thing context, Thing target) {
				Body body = (Body)env;
				String signature = signature(body,context);
				Collection is = target.getThings(AL.is);
				Collection sources = target.getThings(AL.sources);
				String subject = AL.empty(is) ? null : ((Thing)is.iterator().next()).getString(AL.name);
				String url = AL.empty(sources) ? null : ((Thing)sources.iterator().next()).getString(AL.name);
				String text = target.getString(AL.text);
				String content = Str.join(new String[]{url,text}, "\n");
				Collection allSharesTos = getSharesTos(storager,context);
				if (!AL.empty(allSharesTos)) for (Iterator pit = allSharesTos.iterator(); pit.hasNext();){
					Thing peer = (Thing)pit.next();
					target.set(AL._new, AL._true, peer);
					try {
						body.update(peer, subject, content, signature);
					} catch (IOException e) {
						body.error("Siter updating "+subject+" "+text+" "+signature,e);
					}
				}
				return true;
			}
		};
	}
	
	//get count of news not trusted by the 1st peer trusted by self
	public static int pendingNewsCount(Body body) {
		int untrusted = 0;
		Storager storager = body.storager;
		//say for Android, display count of news specific to self owner (1st one trusted peer) 
		Collection trusts = storager.get(AL.trusts, body.self());
		if (!AL.empty(trusts)) {
			Thing peer = (Thing)trusts.iterator().next();
			Collection news = (Collection)peer.get(AL.news, peer);
			if (!AL.empty(news)) {
				for (Iterator it = news.iterator(); it.hasNext();) {
					Thing t = (Thing) it.next();
					Object trust = t.get(AL.trust,peer);
					if (trust == null || !trust.equals(AL._true))
						untrusted++;
				}
			}
		}
		return untrusted;
	}
	
	/*
	//TODO: all that is nt working good so far...
	//... it is intended to create strings from patternless sites
	void count(HashMap map, String token) {
		Object o = map.get(token);
		if (o == null)
			map.put(token,new Integer(1));
		else
			map.put(token,new Integer( ((Integer)o).intValue() + 1 ));
	}
	int summarize(Storager storager, String text, Date time, String path) {
		//TODO: re-use iter building it one step above
		Iter iter = new Iter(Parser.parse(text));
		final int block = 20; //magic average block length
		int blocks = (iter.size() + block - 1) / block;
		if (blocks > 1) {
			HashMap overall = new HashMap();
			HashMap[] phrases = new HashMap[blocks];
			for (int b = 0; b < blocks; b++)
				phrases[b] = new HashMap();
			for (iter.pos(0); iter.has(); iter.next()) {
				count(overall,(String)iter.get());
				count(phrases[iter.cur() / block],(String)iter.get());
			}
			int best = 0;
			for (Iterator it = overall.keySet().iterator(); it.hasNext();) {
				String token = (String)it.next();
				int total = ((Integer)overall.get(token)).intValue();
				int finds = 0;
				for (int b = 0; b < blocks; b++) {
					Object found = phrases[b].get(token);
					if (found != null)
						finds++;					
				}
				int score = total/finds;
				overall.put(token,new Integer(score));
				if (best < score)
					best = score;
			}
			StringBuilder buf = new StringBuilder();
			for (Iterator it = overall.keySet().iterator(); it.hasNext();) {
				String token = (String)it.next();
				int score = ((Integer)overall.get(token)).intValue();
				if (score >= best) {
					if (buf.length() > 0)
						buf.append(' ');
					buf.append(token);
				}
			}
			if (buf.length() > 0) {
				Thing instance = new Thing();
				instance.setString(AL.text,buf.toString());
				instance.set(AL.times, time);

				boolean found = false;
				Collection already = storager.get(instance);
				if (already != null) {
					if (path == null)
						found = true;
					else
						for (Iterator it = already.iterator(); it.hasNext();)
							if  (Array.contains(
								(Collection)((Thing)it.next()).get(AL.sources),
								storager.getNamed(path))) {
								found = true;
								break; // found instance for the same site	
							}
				}
				if (!found)
				{
					instance.store(storager);
					try {
						if (path != null)
							storager.add(instance, AL.sources, path);
					} catch (Exception e) {
						body.error(e.toString(), e);
					}
					return 1;
				}
			}
		}
		return 0;
	}
	*/
	
	public static void matchPeersText(Body body, Collection things, String text, Date time, String permlink, String imgurl){
		MapMap thingPaths = new MapMap();//collector
		int matches = matchThingsText(body,things,text,time,permlink,imgurl,thingPaths);
		if (matches > 0)
			Siter.update(body,null,time,thingPaths,false,null);//forced=false, because may be retrospective
	}
	
	//TODO: move to other place!?
	public static int matchThingsText(Body body, Collection allThings, String text, Date time, String permlink, String imgurl, MapMap thingPaths){
			ContentLocator imager = null;
//TODO: actual image positions based on text MD/HTML parsing!? 
			if (!AL.empty(imgurl)) {
				imager = new ContentLocator();
				TreeMap tm = imager.getMap(permlink);
       			tm.put(new Integer(0), imgurl);
			}
			Iter parse = new Iter(Parser.parse(text));
			int matches = 0;
			long start = System.currentTimeMillis();  
			body.debug("Siter matching start "+permlink);
			for (Object thing: allThings) {
				int match = Siter.match(body.storager, parse, null, (Thing)thing, time, permlink, null, thingPaths, imager, null, null);
				if (match > 0) {
					body.debug("Siter matching found "+((Thing)thing).getName()+" in "+permlink);
					matches += match;
				}
			}
			long stop = System.currentTimeMillis();  
			body.debug("Siter matching stop "+permlink+", took "+Period.toHours(stop-start));
			return matches;
	}

}
