package org.igv.util;

import org.junit.Test;

import java.net.MalformedURLException;

import static org.junit.Assert.*;

public class HttpMappingsTest {

    @Test
    public void mapURL() throws MalformedURLException {

        String url;
        String mappedUrl;

        // Cloudfront URL
        //https://s3.us-east-1.amazonaws.com/igv.broadinstitute.org/data/hg19/1kg/ALL.phase3_shapeit2_mvncall_integrated_v1b.20130502.genotypes.vcf.list
        url = "https://dn7ywbm9isq8j.cloudfront.net/data/hg19/1kg/ALL.phase3_shapeit2_mvncall_integrated_v1b.20130502.genotypes.vcf.list";
        mappedUrl = HttpMappings.mapURL(url);
        assertEquals("https://raw.githubusercontent.com/igvteam/igv-data/refs/heads/main/data/hg19/ALL.phase3_shapeit2_mvncall_integrated_v1b.20130502.genotypes.vcf.list", mappedUrl);

        // Regional URL
        url = "https://s3.us-east-1.amazonaws.com/igv.org.genomes/mm9/refGene.sorted.txt.gz";
        mappedUrl = HttpMappings.mapURL(url);
        assertEquals("https://hgdownload.soe.ucsc.edu/goldenPath/mm9/database/refGene.txt.gz", mappedUrl);

        // Global URL
        url = "https://s3.amazonaws.com/igv.org.genomes/mm9/refGene.sorted.txt.gz";
        mappedUrl = HttpMappings.mapURL(url);
        assertEquals("https://hgdownload.soe.ucsc.edu/goldenPath/mm9/database/refGene.txt.gz", mappedUrl);

    }

    @Test
    public void mapRetiredHosts() throws MalformedURLException {

        // TCGA moved to igv.org, from both the www and data hosts
        String tcgaPath = "/gdac_stddata__2016_01_28/Sample_Set/ACC-TP/foo.seg.txt";
        assertEquals("https://igv.org/tcga" + tcgaPath,
                HttpMappings.mapURL("https://data.broadinstitute.org/igvdata/tcga" + tcgaPath));
        assertEquals("https://igv.org/tcga" + tcgaPath,
                HttpMappings.mapURL("http://www.broadinstitute.org/igvdata/tcga" + tcgaPath));

        // The generic igvdata rule must not shadow the tcga rule above
        assertEquals("https://data.broadinstitute.org/igvdata/foo.bed",
                HttpMappings.mapURL("http://www.broadinstitute.org/igvdata/foo.bed"));

        // Retired hosts are mapped to https even when requested over http
        assertEquals("https://s3.amazonaws.com/igv.broadinstitute.org/data/foo.bed",
                HttpMappings.mapURL("http://igvdata.broadinstitute.org/data/foo.bed"));
        assertEquals("https://s3.amazonaws.com/igv.broadinstitute.org/data/foo.bed",
                HttpMappings.mapURL("http://igv.broadinstitute.org/data/foo.bed"));

        // genepattern bucket, regional form
        assertEquals("https://igv-genepattern-org.s3.us-east-1.amazonaws.com/data/foo.bed",
                HttpMappings.mapURL("https://igv.genepattern.org/data/foo.bed"));

        // NCBI GEO ftp is served over https
        assertEquals("https://ftp.ncbi.nlm.nih.gov/geo/series/GSE1nnn/foo.txt.gz",
                HttpMappings.mapURL("ftp://ftp.ncbi.nlm.nih.gov/geo/series/GSE1nnn/foo.txt.gz"));

        assertEquals("https://dl.dropboxusercontent.com/s/abc/foo.bam",
                HttpMappings.mapURL("https://www.dropbox.com/s/abc/foo.bam"));
    }
}
