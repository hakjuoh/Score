import {Component, OnInit} from '@angular/core';
import {AboutService} from './domain/about.service';
import {ProductInfo} from './domain/about';

const {version} = require('../../../../package.json');

@Component({
  selector: 'srt-about',
  templateUrl: './about.component.html',
  styleUrls: ['./about.component.css']
})
export class AboutComponent implements OnInit {

  productInfos: ProductInfo[] = [];

  constructor(private service: AboutService) {
    const webProductInfo = new ProductInfo();
    webProductInfo.productName = 'srt-web';
    webProductInfo.productVersion = version.replace('-rc', '');

    this.productInfos.push(webProductInfo);
  }

  ngOnInit() {
    this.service.getProductInfo().subscribe(resp => {
      this.productInfos.push(...resp);
    });
  }

}