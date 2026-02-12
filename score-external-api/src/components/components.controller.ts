import { Controller, DefaultValuePipe, ParseUUIDPipe, UseGuards } from '@nestjs/common';
import { Get } from '@nestjs/common';
import { ComponentsService } from './components.service';
import { Param, Query } from '@nestjs/common';
import { AuthGuard } from 'src/auth/auth.guard';
import { ApiBearerAuth, ApiOperation, ApiTags, ApiQuery, ApiOkResponse, ApiProduces, ApiNotFoundResponse, ApiUnauthorizedResponse } from '@nestjs/swagger';
import { Components, ComponentsWithChildren } from './components.dto';
import { Release, Releases } from './releases.dto';
import { Libraries } from './libraries.dto';


@ApiTags('connectSpec (OAGIS) Components')
@Controller('api/components')
@UseGuards(AuthGuard)
export class ComponentsController {

  constructor(
    private readonly componentsService: ComponentsService
  ) { }


  @Get()
  @ApiQuery({ name: 'library', required: false, description: 'Library to return components for.  If not specified it will be the default set by the deployment environment variable or to connectSpec (OAGIS) if environment variable is not set.', example: 'connectSpec' })
  @ApiQuery({ name: 'types', required: false, description: 'Comma-separated list of component types to include such as asccp, bccp, bdt, etc. - refer to the Score User Guide for a complete description of component types.  If not specified, then only asccp will be returned. ', example: 'asccp,bccp' })
  @ApiQuery({ name: 'tags', required: false, description: 'Comma-separated list of component tags applied to the component within Score', example: 'noun,bod' })
  @ApiQuery({ name: 'release', required: false, description: 'library release version (connectSpec (OAGIS) by default) ', example: '10.9.2' })
  @ApiQuery({ name: 'den', required: false, description: 'component DEN (Dictionary Entry Name)', example: 'BOM. BOM' })
  @ApiOperation({
    summary: 'Return metadata for connectCenter (Score) library components. The default is connectSpec (OAGIS) '
      + ' If the release version parameter is not specified it will return the latest version.  If no type is specified, it will return ASCCP components only.'
  })
  @ApiProduces()
  @ApiOkResponse({ description: 'OK', type: Components })
  @ApiUnauthorizedResponse({ description: 'Unauthorized' })
  @ApiNotFoundResponse({ description: 'Release Not Found' })
  @ApiBearerAuth()
  findAll(
    @Query('library') library: string,
    @Query('tags') tags: string,
    @Query('release') release: string,
    @Query('types') types: string,
    @Query('den') componentDen: string
  ) {
    const componentTypes = types ?? 'asccp';
    return this.componentsService.getAllComponentsMetadata(library??this.componentsService.getDefaultLibrary(), false, tags, release, componentTypes, componentDen);
  }

  @Get("children")
  @ApiQuery({ name: 'library', required: false, description: 'Library to return components for.  If not specified it will be the default set by the deployment environment variable or to connectSpec (OAGIS) if not set.', example: 'connectSpec' })
  @ApiQuery({ name: 'tags', required: false, description: 'Comma-separated list of component tags applied to the component within Score', example: 'noun,bod' })
  @ApiQuery({ name: 'release', required: false, description: 'connectSpec (OAGIS) release version', example: '10.9.2' })
  @ApiQuery({ name: 'den', required: false, description: 'connectSpec (OAGIS) component den', example: 'BOM. BOM' })
  @ApiOperation({
    summary: 'Return metadata with child components for connectCenter (Score) library ASCCP and BCCP components. The default is connectSpec (OAGIS) '
      + ' If the release version parameter is not specified it will return the latest version. '
  })
  @ApiProduces()
  @ApiOkResponse({ description: 'OK', type: ComponentsWithChildren })
  @ApiUnauthorizedResponse({ description: 'Unauthorized' })
  @ApiNotFoundResponse({ description: 'Release Not Found' })
  @ApiBearerAuth()
  findAllWithChildren(
    @Query('tags') tags: string,
    @Query('library') library: string,
    @Query('release') release: string,
    @Query('den') componentDen: string
  ) {
    const componentTypes = 'asccp,bccp';
    return this.componentsService.getAllComponentsMetadata(library??this.componentsService.getDefaultLibrary(), true, tags, release, componentTypes, componentDen);
  }


  @Get('uuid/:uuid')
  @ApiQuery({ name: 'release', required: false, description: 'library release version (default connectSpec (OAGIS))', example: '10.9.2' })
  //@ApiQuery({ name: 'schemaType', required: false, description: 'Returned schema format', example: 'xsd' })
  @ApiOperation({
    summary: 'Return component schema for the component with the given UUID -'
      + ' currently XSD is the only schema type supported while others will be added in the future. Note that if the release version parameter is not specified it will return the latest version.'
  })
  @ApiProduces('application/xml')
  @ApiOkResponse({ description: 'OK' })
  @ApiUnauthorizedResponse({ description: 'Unauthorized' })
  @ApiNotFoundResponse({ description: 'Release Not Found' })
  @ApiBearerAuth()
  findComponent(
    @Param('uuid') uuid: string,
    @Query('library') library: string,
    @Query('release') release: string,
    //@Query('schemaType') schemaType: string
  ) {
    const schemaType = 'xsd';
    return this.componentsService.getStandaloneComponent(library??this.componentsService.getDefaultLibrary(), uuid, schemaType, release);
  }

  @Get('releases')
  @ApiOperation({
    summary: 'Return metadata for all releases available in connectCenter (Score). '
      + ' If no library is given, then releases will be for the library name specified in the default_library environment variable or if the environment variable is not set, '
      + ' then the default library will be connectSpec (OAGIS).'
  })
  @ApiProduces()
  @ApiOkResponse({ description: 'OK', type: Releases })
  @ApiUnauthorizedResponse({ description: 'Unauthorized' })
  @ApiBearerAuth()
  getReleases(
    @Query('library') library: string,
  ) {
    return this.componentsService.getReleases(library??this.componentsService.getDefaultLibrary());
  }

  @Get('releases/latest')
  @ApiOperation({
    summary: 'Return metadata for the latest release available in connectCenter (Score) for a given library.'
      + ' If no library is given, then this release will be for the library name specified in the default_library environment variable or if the environment variable is not set, '
      + ' then the default library will be connectSpec (OAGIS).'
  })
  @ApiProduces()
  @ApiOkResponse({ description: 'OK', type: Release })
  @ApiUnauthorizedResponse({ description: 'Unauthorized' })
  @ApiBearerAuth()
  getLatestRelease(
    @Query('library') library: string,
  ) {
    return this.componentsService.getLatestRelease(library??this.componentsService.getDefaultLibrary());
  }


  @Get('libraries')
  @ApiOperation({ summary: 'Return metadata for all libraries available in connectCenter (Score)' })
  @ApiProduces()
  @ApiOkResponse({ description: 'OK', type: Libraries })
  @ApiUnauthorizedResponse({ description: 'Unauthorized' })
  @ApiBearerAuth()
  getLibraries() {
    return this.componentsService.getLibraries();
  }


  /*
    @Get('latest_release')
    @ApiOperation({ deprecated: true, summary: 'Return description of the latest connectSpec (OAGIS) release available in connectCenter (Score)' })
    @ApiOkResponse({ description: 'Data retrieved',type: String})
    @ApiUnauthorizedResponse({ description: 'Unauthorized'})
    @ApiBearerAuth()
    getLatestReleaseDeprecated() {
      return this.componentsService.getLatestRelease().then(value => { return value.releaseNum;})
    }
  */
}